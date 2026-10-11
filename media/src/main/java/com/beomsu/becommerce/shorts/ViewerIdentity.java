package com.beomsu.becommerce.shorts;

import java.util.Objects;

/**
 * 숏폼 시청자 식별(R27) — 로그인 사용자는 {@code userId}(인증 principal에서 얻는다), 비로그인은
 * 클라이언트가 보낸 익명 식별자다. 둘 중 정확히 하나만 채워진다 — 서버가 지어내지 않는다
 * (익명 식별자는 신원이 아니라 재방문 묶음 키일 뿐이라 클라이언트 값을 믿어도 안전하다,
 * {@code userId}와 달리 IDOR 우려가 없다).
 */
public record ViewerIdentity(Long userId, String anonymousId) {

    public ViewerIdentity {
        if ((userId == null) == (anonymousId == null)) {
            throw new IllegalArgumentException("userId와 anonymousId 중 정확히 하나만 있어야 합니다");
        }
    }

    public static ViewerIdentity ofUser(long userId) {
        return new ViewerIdentity(userId, null);
    }

    public static ViewerIdentity ofAnonymous(String anonymousId) {
        return new ViewerIdentity(null, Objects.requireNonNull(anonymousId));
    }

    public boolean isAnonymous() {
        return userId == null;
    }
}
