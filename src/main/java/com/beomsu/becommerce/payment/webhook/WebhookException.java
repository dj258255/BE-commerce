package com.beomsu.becommerce.payment.webhook;

import com.beomsu.becommerce.shared.DomainException;

/**
 * 웹훅 처리 도메인 예외.
 *
 * <p>code는 10-API-스펙 문서의 에러 코드 체계와 일치한다. 서명 검증 실패
 * ({@code INVALID_WEBHOOK_SIGNATURE})만 컨트롤러가 401로 응답하고, 그 외는 저장 후 200을 준다.
 */
public class WebhookException extends DomainException {

    public WebhookException(String code, String message) {
        super(code, message);
    }
}
