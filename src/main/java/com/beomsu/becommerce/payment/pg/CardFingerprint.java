package com.beomsu.becommerce.payment.pg;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 같은 카드를 여러 결제에 걸쳐 묶는 키.
 *
 * <p><b>왜 필요한가</b>: 사후 탐지가 이력을 묶는 키로 쓰던 것은 {@code paymentKey} 인데 그것은
 * 결제마다 새로 발급된다. 그래서 창에 늘 이번 건 하나만 들어왔고, 같은 카드의 과거 결제를 보는
 * 피처(창 건수·금액 계단·과거 중앙값 대비 배수)가 사실상 죽어 있었다.
 *
 * <p><b>카드 데이터를 저장하지 않는다.</b> 여기 들어오는 것은 PG 가 이미 마스킹해서 준 번호와
 * 발급사 코드이고, 그것을 다시 <b>단방향 해시</b>로 바꿔 그 값만 남긴다. 원문은 어디에도 안
 * 적는다. 해시를 되돌려 카드번호를 얻을 수 없고, 얻더라도 마스킹된 값이다.
 *
 * <p><b>이 키는 카드를 유일하게 가리키지 않는다.</b> 마스킹은 앞 여덟 자리와 뒤 몇 자리만 남기므로
 * <b>같은 발급사·같은 BIN 의 다른 카드가 같은 키를 받을 수 있다.</b> 묶임이 과할 수는 있어도
 * 갈라지지는 않는다는 뜻이라, 탐지에서는 <b>거짓 양성 쪽으로 기운다.</b> 카드를 유일하게 가리키는
 * 값이 필요하면 PG 가 주는 빌링키를 받아야 하고 그것은 자동결제 연동이 따로 필요하다.
 */
public final class CardFingerprint {

    private CardFingerprint() {
    }

    /**
     * 마스킹된 카드번호와 발급사 코드로 키를 만든다.
     *
     * @return 64자 16진수 해시. 둘 다 비어 있으면 {@code null}(묶을 근거가 없다)
     */
    public static String of(String maskedNumber, String issuerCode) {
        String number = normalize(maskedNumber);
        String issuer = normalize(issuerCode);
        if (number == null && issuer == null) {
            return null;
        }
        return sha256(issuer + "|" + number);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 JDK 표준이라 안 뜬다. 뜨면 설정이 깨진 것이므로 조용히 넘기면 안 된다.
            throw new IllegalStateException("SHA-256 을 못 찾았습니다", e);
        }
    }
}
