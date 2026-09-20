/**
 * 웹훅 수신/서명 검증 하위 패키지 — payment 모듈의 명명 인터페이스({@code webhook}).
 *
 * <p>차지백 등 다른 모듈의 웹훅 컨트롤러가 <b>같은 HMAC 서명 검증기</b>
 * ({@link com.beomsu.becommerce.payment.webhook.WebhookSignatureVerifier})를 재사용할 수 있도록 이 패키지를
 * 명명 인터페이스로 노출한다. 노출 대상은 검증기뿐이며, 나머지(수신 서비스·엔티티)는 payment 내부
 * 구현이지만 같은 패키지라 함께 인터페이스에 속한다 — 재사용은 검증기 컴포넌트로 한정해 쓴다.
 */
@org.springframework.modulith.NamedInterface("webhook")
package com.beomsu.becommerce.payment.webhook;
