package com.beomsu.becommerce.shared.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 필드 단위 암호화 — AES-256-GCM.
 *
 * <p>계좌번호처럼 「개인정보의 안전성 확보조치 기준」상 암호화 의무 대상 필드를 암호화한다.
 * GCM은 인증 태그를 포함해 <b>변조를 감지</b>한다(CBC와의 차이). 암호문마다 무작위 IV를 앞에 붙여
 * 같은 평문도 매번 다른 암호문이 되게 한다.
 *
 * <p>키는 프로퍼티로 주입한다(데모). 운영에서는 <b>envelope encryption</b>으로 교체한다 —
 * 데이터는 DEK로, DEK는 KMS 마스터키(KEK)로 암호화해 함께 저장하고, DEK만 이 자리에 온다.
 * 그 경우 이 클래스의 인터페이스는 그대로 두고 키 공급만 KMS로 바꾸면 된다.
 */
@Component
public class AesGcmFieldCipher implements FieldCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;      // GCM 권장 96비트
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    // 키는 코드에 두지 않는다 — 설정/환경변수({@code app.crypto.key})로만 주입한다.
    // 미설정 시 기동 실패시켜, 공개된 기본 키로 배포되는 사고를 원천 차단한다.
    public AesGcmFieldCipher(@Value("${app.crypto.key}") String keyString) {
        if (keyString == null || keyString.isBlank()) {
            throw new IllegalStateException("app.crypto.key 미설정 — 암호화 키를 환경변수/시크릿으로 주입해야 합니다.");
        }
        byte[] keyBytes = keyString.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (keyBytes.length != 32) {
            throw new IllegalStateException("AES-256 키는 32바이트여야 합니다(현재 " + keyBytes.length + ").");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // IV(12) + 암호문+태그 를 이어붙여 base64
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("필드 암호화 실패", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(ciphertext);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] pt = cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
            return new String(pt, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            // GCM 인증 실패(변조) 포함
            throw new IllegalStateException("필드 복호화 실패(변조 의심)", e);
        }
    }
}
