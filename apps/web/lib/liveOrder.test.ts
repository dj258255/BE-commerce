import { describe, expect, it } from 'vitest';
import {
  applyOrderResult,
  applyPaymentResult,
  canPlaceOrder,
  INITIAL_LIVE_ORDER_STATE,
  rejectionMessage,
} from './liveOrder';

describe('R11.1: 주문 생성 응답(201)은 결제 대기 상태로 바뀐다', () => {
  it('orderNo·totalAmount를 그대로 담은 readyToPay가 된다', () => {
    const state = applyOrderResult({ status: 201, body: { orderNo: 'ORD-1', totalAmount: 9900, expiresAt: 'x' } });
    expect(state).toEqual({ phase: 'readyToPay', orderNo: 'ORD-1', totalAmount: 9900 });
  });
});

describe('R5.2: 비로그인 주문 호출(401)은 로그인 안내 상태가 된다', () => {
  it('unauthenticated로 바뀐다', () => {
    const state = applyOrderResult({ status: 401, body: null });
    expect(state).toEqual({ phase: 'unauthenticated' });
  });
});

describe('R14.2: 매진(409 LIMITED_QUANTITY_SOLD_OUT)은 매진 문구로 거절된다', () => {
  it('rejected 상태에 매진 문구가 담긴다', () => {
    const state = applyOrderResult({ status: 409, body: { code: 'LIMITED_QUANTITY_SOLD_OUT' } });
    expect(state).toEqual({ phase: 'rejected', reason: rejectionMessage('LIMITED_QUANTITY_SOLD_OUT') });
    expect((state as { reason: string }).reason).toContain('매진');
  });
});

describe('R10.2: 고정이 바뀜(409 NOTHING_PINNED)은 카드 재확인 문구로 거절된다', () => {
  it('rejected 상태에 "바뀌었습니다" 문구가 담긴다', () => {
    const state = applyOrderResult({ status: 409, body: { code: 'NOTHING_PINNED' } });
    expect((state as { reason: string }).reason).toContain('바뀌었습니다');
  });
});

describe('R11.1: 결제 승인 응답(200)은 완료 상태가 된다', () => {
  it('paid로 바뀌고 orderNo를 유지한다', () => {
    const state = applyPaymentResult('ORD-1', 9900, { status: 200, body: {} });
    expect(state).toEqual({ phase: 'paid', orderNo: 'ORD-1' });
  });
});

describe('R11.2: 결제 승인 실패(400)는 실패 사유와 함께 재시도 가능한 상태가 된다', () => {
  it('paymentFailed에 서버 message를 그대로 담는다', () => {
    const state = applyPaymentResult('ORD-1', 9900, { status: 400, body: { message: '카드 한도 초과' } });
    expect(state).toEqual({ phase: 'paymentFailed', orderNo: 'ORD-1', totalAmount: 9900, reason: '카드 한도 초과' });
  });

  it('message가 없어도 HTTP 상태를 담은 기본 문구를 쓴다', () => {
    const state = applyPaymentResult('ORD-1', 9900, { status: 500, body: null });
    expect((state as { reason: string }).reason).toContain('500');
  });
});

describe('R11.2: 결제 실패 뒤 재시도가 성공하면 paid로 바뀐다', () => {
  it('실패(400) 다음 같은 orderNo로 재시도(200)하면 paid가 된다', () => {
    const failed = applyPaymentResult('ORD-1', 9900, { status: 400, body: { message: '카드 한도 초과' } });
    expect(failed).toEqual({ phase: 'paymentFailed', orderNo: 'ORD-1', totalAmount: 9900, reason: '카드 한도 초과' });
    // 재시도 버튼은 실패 상태의 같은 orderNo·totalAmount로 confirmPayment를 다시 부른다(LiveViewer.tsx).
    const retried = applyPaymentResult(
      (failed as { orderNo: string }).orderNo,
      (failed as { totalAmount: number }).totalAmount,
      { status: 200, body: {} },
    );
    expect(retried).toEqual({ phase: 'paid', orderNo: 'ORD-1' });
  });

  it('재시도도 실패하면 새 사유로 다시 paymentFailed가 되어 또 재시도할 수 있다', () => {
    const failed = applyPaymentResult('ORD-1', 9900, { status: 400, body: { message: '카드 한도 초과' } });
    const retriedFailed = applyPaymentResult(
      (failed as { orderNo: string }).orderNo,
      (failed as { totalAmount: number }).totalAmount,
      { status: 500, body: null },
    );
    expect(retriedFailed).toEqual({
      phase: 'paymentFailed',
      orderNo: 'ORD-1',
      totalAmount: 9900,
      reason: '결제 승인에 실패했습니다(HTTP 500)',
    });
  });
});

describe('R13: 결제 결과 UNKNOWN(202)은 확정도 실패도 아닌 대기 상태로 유지된다', () => {
  it('pending으로 바뀌고(실패로 단정하지 않는다) orderNo·totalAmount를 유지한다', () => {
    const state = applyPaymentResult('ORD-1', 9900, { status: 202, body: {} });
    expect(state).toEqual({ phase: 'pending', orderNo: 'ORD-1', totalAmount: 9900 });
  });
});

describe('R14.1: 매진되거나 고정이 풀리면 바로 주문 버튼이 비활성화된다', () => {
  it('카드가 없으면(고정 해제) 누를 수 없다', () => {
    expect(canPlaceOrder(null, INITIAL_LIVE_ORDER_STATE)).toBe(false);
  });

  it('남은 수량이 0이면(매진) 누를 수 없다', () => {
    expect(canPlaceOrder({ remainingQuantity: 0 }, INITIAL_LIVE_ORDER_STATE)).toBe(false);
  });

  it('남은 수량이 있고 idle이면 누를 수 있다', () => {
    expect(canPlaceOrder({ remainingQuantity: 1 }, INITIAL_LIVE_ORDER_STATE)).toBe(true);
  });

  it('주문·결제가 진행 중(readyToPay)이면 중복 클릭을 막아 누를 수 없다', () => {
    expect(canPlaceOrder({ remainingQuantity: 1 }, { phase: 'readyToPay', orderNo: 'ORD-1', totalAmount: 9900 })).toBe(
      false,
    );
  });

  it('결제가 끝난(paid) 뒤에는 남은 수량이 있으면 다시 누를 수 있다', () => {
    expect(canPlaceOrder({ remainingQuantity: 1 }, { phase: 'paid', orderNo: 'ORD-1' })).toBe(true);
  });
});
