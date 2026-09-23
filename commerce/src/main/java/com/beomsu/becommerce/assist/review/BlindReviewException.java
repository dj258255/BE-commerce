package com.beomsu.becommerce.assist.review;

import com.beomsu.becommerce.shared.DomainException;

/**
 * 블라인드 리뷰 규칙 위반.
 *
 * <p><b>왜 전용 예외인가</b>: 순서를 어긴 호출을 {@code IllegalStateException} 으로 두면
 * 500 이 나가고, 호출자는 <b>버그인지 의도된 차단인지</b> 구분할 수 없다.
 * 이 실험에서 순서 위반은 오류가 아니라 <b>설계된 거절</b>이다. 그렇게 응답해야 한다.
 */
public class BlindReviewException extends DomainException {

    public BlindReviewException(String code, String message) {
        super(code, message);
    }

    /** 순서를 어겼다 — 409. 다시 시도한다고 되는 게 아니라 절차가 틀렸다. */
    static BlindReviewException outOfOrder(String message) {
        return new BlindReviewException("REVIEW_OUT_OF_ORDER", message);
    }

    static BlindReviewException notFound(long id) {
        return new BlindReviewException("REVIEW_NOT_FOUND", "리뷰를 찾을 수 없습니다: " + id);
    }

    static BlindReviewException invalid(String message) {
        return new BlindReviewException("INVALID_REVIEW_INPUT", message);
    }
}
