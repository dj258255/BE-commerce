package com.beomsu.becommerce.shared.crypto;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import io.micrometer.core.instrument.Counter;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 필드 단위 암호화 — <b>Envelope Encryption(DEK/KEK)</b>.
 *
 * <p>{@link AesGcmFieldCipher}(단일 키)를 실서비스/금융권 방식으로 확장한다. 데이터는 매번 새
 * <b>DEK(Data Encryption Key)</b>로 AES-256-GCM 암호화하고, 그 DEK를 <b>KEK(마스터키, KMS 보관)</b>로
 * 감싸(wrap) 암호문과 함께 저장한다. 이렇게 하면
 * <ol>
 *   <li>키 로테이션 시 <b>모든 데이터를 재암호화하지 않고</b> 작은 DEK만 새 KEK로 재-wrap하면 된다
 *       ({@link #rewrapToCurrent}).</li>
 *   <li>마스터키(KEK)가 KMS 밖으로 나오지 않는다({@link MasterKeyProvider}가 실 KMS로 교체 가능).</li>
 * </ol>
 *
 * <h2>암호문 포맷</h2>
 * <pre>{@code
 *   env:{version}:{base64(wrapBlob)}:{base64(dataBlob)}
 * }</pre>
 * <ul>
 *   <li>{@code version} — DEK를 감싼 KEK의 버전(복호화 때 어느 KEK인지 안다).</li>
 *   <li>{@code dataBlob} — 데이터 암호문: {@code iv(12) + ct + tag}, DEK로 GCM.</li>
 *   <li>{@code wrapBlob} — 감싼 DEK: {@code iv(12) + wrappedDek + tag}, KEK로 GCM.</li>
 * </ul>
 * 버전을 prefix로 실으므로 로테이션 후에도 옛 암호문을 옛 KEK로 복호화할 수 있다.
 *
 * <p><b>주의(스키마):</b> envelope 암호문은 wrap된 DEK를 함께 실어 단일 키 방식보다 길다
 * (base64 wrapBlob 60여 바이트 + 버전 prefix). 민감 컬럼 길이를 넉넉히 잡아야 한다.
 *
 * <h2>빈 선택</h2>
 * {@code app.crypto.mode=envelope}(기본, {@code matchIfMissing=true})일 때만 생성되며 {@code @Primary}라
 * {@link FieldCipher} 주입 시 우선한다. {@link AesGcmFieldCipher} 빈은 그대로 남지만 주입되지 않는다.
 * {@code mode=simple}로 두면 이 빈이 만들어지지 않아 AesGcm이 주입된다.
 */
@Component
@Primary
@ConditionalOnProperty(name = "app.crypto.mode", havingValue = "envelope", matchIfMissing = true)
public class EnvelopeFieldCipher implements FieldCipher {

    private static final String PREFIX = "env:";

    /**
     * 평문으로 읽힌 횟수. 0이 되고 재암호화가 끝나면 하위호환 분기를 제거할 수 있다.
     *
     * <p>이 값이 계속 0보다 크면 <b>어딘가에 아직 평문이 남아 있다</b>는 뜻이다.
     * 지표가 없으면 "언젠가 다 암호화됐겠지"로 끝나고 분기는 영원히 남는다.
     */
    private final Counter legacyPlaintextReads;

    /** 이관 완료 선언 스위치. true면 평문을 통과시키지 않고 실패시킨다. */
    private final boolean failOnLegacyPlaintext;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;       // GCM 권장 96비트
    private static final int TAG_LENGTH_BITS = 128;
    private static final int DEK_BITS = 256;        // AES-256 DEK

    private final MasterKeyProvider masterKeys;
    private final SecureRandom random = new SecureRandom();

    /**
     * 테스트용 간편 생성자. 지표는 버려지고 평문은 통과시킨다.
     *
     * <p><b>스프링은 이걸 쓰지 않는다.</b> 생성자가 둘이면 스프링이 어느 쪽인지 못 정해
     * 기본 생성자를 찾다가 실패한다. 그래서 아래 생성자에 {@code @Autowired}를 달아
     * 주입 대상을 하나로 못 박았다.
     */
    public EnvelopeFieldCipher(MasterKeyProvider masterKeys) {
        this(masterKeys, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), false);
    }

    /**
     * @param failOnLegacyPlaintext 이관 완료 선언 스위치. 재암호화가 끝나 평문 읽기 지표가
     *                              0이 된 뒤 켜면, 남아 있던 평문이 조용히 통과하지 않고 실패한다
     */
    @org.springframework.beans.factory.annotation.Autowired
    public EnvelopeFieldCipher(MasterKeyProvider masterKeys,
                               io.micrometer.core.instrument.MeterRegistry meterRegistry,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${app.crypto.fail-on-legacy-plaintext:false}")
                               boolean failOnLegacyPlaintext) {
        this.masterKeys = masterKeys;
        this.legacyPlaintextReads = meterRegistry.counter("crypto.legacy.plaintext.reads");
        this.failOnLegacyPlaintext = failOnLegacyPlaintext;
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            // 1) 무작위 DEK(AES-256).
            SecretKey dek = generateDek();
            // 2) 데이터 암호화: DEK + 무작위 iv → iv+ct+tag.
            byte[] dataBlob = gcmEncrypt(dek, plaintext.getBytes(StandardCharsets.UTF_8));
            // 3) DEK wrap: currentKEK + 무작위 iv → iv+wrappedDek+tag.
            String version = masterKeys.currentVersion();
            byte[] wrapBlob = gcmEncrypt(masterKeys.keyFor(version), dek.getEncoded());
            // 4) 버전을 실은 포맷.
            return format(version, wrapBlob, dataBlob);
        } catch (Exception e) {
            throw new IllegalStateException("필드 암호화 실패", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        // 레거시 평문 행 하위호환 — env: 프리픽스가 없으면 암호화 이전에 저장된 평문으로 보고 그대로 반환한다.
        //
        // <이 분기는 끝이 있어야 한다.> 남겨두면 "암호화를 우회하는 형식"이 영구히 허용되고,
        // 손상되거나 바뀐 값도 조용히 평문으로 받아들인다. 그래서 <셀 수 있게> 만들었다.
        // 비밀번호 이관에 password.hash.legacy.count 게이지를 둔 것과 같은 이유다 —
        // 끝낼 수 없는 이관이라면 최소한 <끝을 판정>할 수 있어야 한다.
        //
        // legacyPlaintextReads 가 0이 되고 재암호화 배치가 끝나면 이 분기를 제거한다.
        if (!ciphertext.startsWith(PREFIX)) {
            legacyPlaintextReads.increment();
            if (failOnLegacyPlaintext) {
                // 이관 완료 선언 후에는 알 수 없는 형식을 조용히 통과시키지 않는다.
                throw new IllegalStateException(
                        "암호화 이전 평문으로 보이는 값입니다. 이관이 끝난 환경에서는 허용하지 않습니다.");
            }
            return ciphertext;
        }
        Parsed p = parse(ciphertext);
        try {
            // 버전으로 KEK를 찾아 DEK를 복원(unwrap) → 데이터 복호화.
            SecretKey kek = masterKeys.keyFor(p.version);
            byte[] dekBytes = gcmDecrypt(kek, p.wrapBlob);
            SecretKey dek = new SecretKeySpec(dekBytes, "AES");
            byte[] pt = gcmDecrypt(dek, p.dataBlob);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 알 수 없는 버전 등 — 그대로 전파(테스트가 구분)
            throw e;
        } catch (Exception e) {
            // GCM 인증 실패(변조) 포함
            throw new IllegalStateException("복호화 실패", e);
        }
    }

    /**
     * 키 로테이션 — 데이터 재암호화 없이 DEK만 current KEK로 재-wrap한다.
     *
     * <p>암호문의 버전이 이미 current면 그대로 반환한다. 다르면 옛 KEK로 DEK를 unwrap한 뒤
     * <b>current KEK로 다시 wrap</b>하고, {@code dataBlob}(실제 데이터 암호문)은 <b>그대로 둔 채</b>
     * 새 {@code env:{current}:{newWrap}:{dataBlob}}를 반환한다. 이것이 envelope의 핵심 이점 —
     * 수백만 행을 재암호화하는 대신 각 행의 작은 DEK만 재-wrap하면 된다.
     */
    public String rewrapToCurrent(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        Parsed p = parse(ciphertext);
        String current = masterKeys.currentVersion();
        if (p.version.equals(current)) {
            return ciphertext; // 이미 current — 할 일 없음.
        }
        try {
            // 옛 KEK로 DEK unwrap → current KEK로 다시 wrap. dataBlob은 불변.
            SecretKey oldKek = masterKeys.keyFor(p.version);
            byte[] dekBytes = gcmDecrypt(oldKek, p.wrapBlob);
            byte[] newWrapBlob = gcmEncrypt(masterKeys.keyFor(current), dekBytes);
            return format(current, newWrapBlob, p.dataBlob);
        } catch (Exception e) {
            throw new IllegalStateException("재-wrap 실패", e);
        }
    }

    // --- 내부 헬퍼 ---

    private SecretKey generateDek() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(DEK_BITS, random);
        return kg.generateKey();
    }

    /** GCM 암호화: 무작위 iv를 앞에 붙여 {@code iv(12) + ct + tag} 반환. */
    private byte[] gcmEncrypt(SecretKey key, byte[] plain) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        byte[] ct = cipher.doFinal(plain);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    /** GCM 복호화: {@code iv(12) + ct + tag} 형식을 받아 평문 반환. */
    private byte[] gcmDecrypt(SecretKey key, byte[] blob) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(blob, 0, iv, 0, IV_LENGTH);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        return cipher.doFinal(blob, IV_LENGTH, blob.length - IV_LENGTH);
    }

    private static String format(String version, byte[] wrapBlob, byte[] dataBlob) {
        Base64.Encoder b64 = Base64.getEncoder();
        return PREFIX + version + ":" + b64.encodeToString(wrapBlob) + ":" + b64.encodeToString(dataBlob);
    }

    private static Parsed parse(String ciphertext) {
        if (!ciphertext.startsWith(PREFIX)) {
            throw new IllegalStateException("envelope 형식이 아닙니다(env: 프리픽스 없음).");
        }
        // "env:" 이후를 version:wrap:data 3조각으로.
        String body = ciphertext.substring(PREFIX.length());
        String[] parts = body.split(":", 3);
        if (parts.length != 3 || parts[0].isBlank()) {
            throw new IllegalStateException("envelope 형식이 손상됐습니다.");
        }
        try {
            Base64.Decoder b64 = Base64.getDecoder();
            return new Parsed(parts[0], b64.decode(parts[1]), b64.decode(parts[2]));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("envelope base64 디코드 실패(변조 의심)", e);
        }
    }

    private record Parsed(String version, byte[] wrapBlob, byte[] dataBlob) {
    }
}
