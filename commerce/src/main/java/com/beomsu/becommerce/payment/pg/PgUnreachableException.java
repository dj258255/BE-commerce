package com.beomsu.becommerce.payment.pg;

/**
 * PG에 요청이 <b>닿지도 못했다</b>는 신호 — 연결 수립 실패나 DNS 실패처럼 요청 바이트가 나가기 전에
 * 끝난 경우에만 던진다.
 *
 * <p><b>왜 전용 예외가 필요한가</b>: 다른 PG로 넘겨도 되는 실패와 넘기면 안 되는 실패를 가르는 기준이
 * "예외냐 아니냐"가 될 수 없기 때문이다. 어댑터는 미확정도 예외로 표현한다 — 일시 오류 응답
 * ({@code PROVIDER_ERROR})은 승인이 났는지 알 수 없는 상태이고, 승인 금액 불일치는 PG가 <b>이미
 * 승인을 마친 뒤</b> 나온다. 라우팅이 예외를 싸잡아 "요청이 안 닿았다"로 읽으면, 넘기지 않겠다고
 * 선언한 바로 그 상태가 다른 PG로 넘어가 이중 결제가 된다.
 *
 * <p>그래서 넘겨도 되는 경우를 <b>명시적 신호</b>로만 표현한다. 이 예외가 아니면 넘기지 않는다.
 */
public class PgUnreachableException extends RuntimeException {

    public PgUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 요청이 전송되기 전에 끝난 실패인지 — 연결 거부·호스트 미해석만 해당한다. */
    public static boolean isConnectFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.ConnectException || t instanceof java.net.UnknownHostException) {
                return true;
            }
            if (t == t.getCause()) {
                break;
            }
        }
        return false;
    }
}
