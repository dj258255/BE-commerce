package com.beomsu.becommerce.shared.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 프로퍼티에서 버전별 KEK를 읽는 {@link MasterKeyProvider} — 로컬/데모용.
 *
 * <p>실 운영에서는 이 빈을 AWS KMS/Vault 구현으로 교체한다(마스터키를 프로세스 밖 KMS에 보관).
 * 여기서는 {@code app.crypto.kek.*} 프로퍼티에서 32바이트(AES-256) KEK를 버전별로 읽어 Map으로 구성한다.
 *
 * <ul>
 *   <li>{@code app.crypto.kek.v1} — 필수. 32바이트 검증, 미설정·길이오류 시 fail-fast(공개 기본키 배포 사고 차단).</li>
 *   <li>{@code app.crypto.kek.v2} — 옵션. 있으면 등록(로테이션 데모용).</li>
 *   <li>{@code app.crypto.kek.current} — current 버전. 등록된 버전이어야 함(아니면 fail-fast).</li>
 * </ul>
 */
@Component
public class PropertyMasterKeyProvider implements MasterKeyProvider {

    private static final int KEK_LENGTH = 32; // AES-256

    private final String current;
    private final Map<String, SecretKey> keys = new LinkedHashMap<>();

    public PropertyMasterKeyProvider(
            @Value("${app.crypto.kek.current:v1}") String current,
            @Value("${app.crypto.kek.v1:}") String v1,
            @Value("${app.crypto.kek.v2:}") String v2) {

        // v1은 필수 — 미설정/길이오류면 기동 실패.
        keys.put("v1", toKey("v1", v1, true));
        // v2는 옵션 — 값이 있을 때만 등록(로테이션 데모).
        if (v2 != null && !v2.isBlank()) {
            keys.put("v2", toKey("v2", v2, false));
        }

        if (current == null || current.isBlank()) {
            throw new IllegalStateException("app.crypto.kek.current 미설정.");
        }
        if (!keys.containsKey(current)) {
            throw new IllegalStateException(
                    "app.crypto.kek.current=" + current + " 이(가) 등록된 KEK 버전이 아닙니다. 등록: " + keys.keySet());
        }
        this.current = current;
    }

    private static SecretKey toKey(String version, String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw new IllegalStateException(
                        "app.crypto.kek." + version + " 미설정 — KEK(마스터키)를 환경변수/시크릿으로 주입해야 합니다.");
            }
            return null;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != KEK_LENGTH) {
            throw new IllegalStateException(
                    "KEK(" + version + ")는 32바이트여야 합니다(현재 " + bytes.length + ").");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    @Override
    public String currentVersion() {
        return current;
    }

    @Override
    public SecretKey keyFor(String version) {
        SecretKey key = keys.get(version);
        if (key == null) {
            throw new IllegalArgumentException("알 수 없는 KEK 버전: " + version);
        }
        return key;
    }
}
