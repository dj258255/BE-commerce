package com.beomsu.becommerce.payment.pg;

/**
 * PG가 <b>다시 시도해도 결과가 같다</b>고 확정한 취소 실패.
 *
 * <p>취소 실패에는 성격이 다른 둘이 섞여 있다. 네트워크 오류나 PG 내부 오류는 잠시 뒤 다시 보내면
 * 성공할 수 있지만, "그런 결제가 없다"(NOT_FOUND_PAYMENT)나 "환불 가능 기간이 지났다"
 * (EXCEED_MAX_REFUND_DUE)는 몇 번을 보내도 같은 답이 온다.
 *
 * <p>둘을 같은 예외로 던지면 보상 재시도가 <b>이길 수 없는 요청에 예산을 다 태우고</b>, 그동안 실제로
 * 사람이 봐야 할 건이 알림에 묻힌다. 그래서 재시도가 무의미한 경우만 이 예외로 구분해, 보상 실행기가
 * 재시도 없이 곧바로 운영자에게 넘기게 한다.
 */
public class PgCancelNotRetryableException extends RuntimeException {

    private final String pgErrorCode;

    public PgCancelNotRetryableException(String pgErrorCode, String message) {
        super("취소 재시도 무의미(%s): %s".formatted(pgErrorCode, message));
        this.pgErrorCode = pgErrorCode;
    }

    public String pgErrorCode() {
        return pgErrorCode;
    }
}
