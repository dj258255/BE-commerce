/**
 * 고정 상품 카드 "바로 주문"(R11) 화면이 쓰는 순수 로직 — DOM·네트워크를 모른다(`lib/shortsFeed.ts`와
 * 같은 이유로 떼어 둔다: fetch 목 없이 테스트할 수 있다).
 *
 * 흐름은 기존 두 API를 그대로 잇는다(R11 "기존 주문·결제 흐름으로"): 주문 생성
 * `POST /api/v1/live/broadcasts/{id}/orders` → 결제 승인 `POST /api/v1/payments/confirm`.
 * 이 파일은 그 두 HTTP 응답을 화면 상태로 바꾸는 매핑만 하고, 실제 fetch는
 * `components/LiveViewer.tsx`가 한다.
 */

export type LiveOrderState =
  | { phase: 'idle' }
  | { phase: 'placing' }
  | { phase: 'readyToPay'; orderNo: string; totalAmount: number }
  | { phase: 'confirming'; orderNo: string; totalAmount: number }
  /** 결제 결과 UNKNOWN(ADR-007) — 기존 결제 복구 흐름이 확정할 때까지 기다린다(R13과 같은 원칙). */
  | { phase: 'pending'; orderNo: string; totalAmount: number }
  | { phase: 'paid'; orderNo: string }
  /** R11.2: 실패 사유와 함께 재시도할 수 있는 상태. */
  | { phase: 'paymentFailed'; orderNo: string; totalAmount: number; reason: string }
  /** R10.2·R14.2: 주문 생성 자체가 거절됨(고정이 바뀌었거나 매진). */
  | { phase: 'rejected'; reason: string }
  /** R5.2: 비로그인 호출이 401로 거절됨. */
  | { phase: 'unauthenticated' };

export const INITIAL_LIVE_ORDER_STATE: LiveOrderState = { phase: 'idle' };

/**
 * 지금 "바로 주문" 버튼을 누를 수 있는가.
 *
 * <p>R14.1: 매진(남은 수량 0)이거나 카드가 없으면(고정 해제) 항상 막는다 — 재생 시점을 기다리지
 * 않고 즉시 반영된 값을 그대로 쓴다(QUANTITY_CHANGED는 `lib/livePin.ts`가 effectiveAt 게이트 없이
 * 바로 적용해 둔 값이라서다). 이미 주문·결제가 진행 중인 단계에서도 막아 중복 클릭을 막는다.
 */
export function canPlaceOrder(card: { remainingQuantity: number } | null, state: LiveOrderState): boolean {
  if (card == null || card.remainingQuantity <= 0) return false;
  return (
    state.phase === 'idle' ||
    state.phase === 'rejected' ||
    state.phase === 'unauthenticated' ||
    state.phase === 'paid' ||
    state.phase === 'paymentFailed'
  );
}

/** 주문 생성이 409로 거절된 사유 코드별 화면 문구 — R10.2(고정이 바뀜)·R14.2(매진)를 구분해 보여준다. */
export function rejectionMessage(code: string | undefined): string {
  switch (code) {
    case 'LIMITED_QUANTITY_SOLD_OUT':
      return '방금 매진됐습니다. 이 상품은 더 주문할 수 없습니다.';
    case 'NOTHING_PINNED':
      return '고정 상품이 바뀌었습니다. 카드를 다시 확인한 뒤 주문해 주세요.';
    default:
      return '주문할 수 없습니다.';
  }
}

export type HttpOutcome = { status: number; body: Record<string, unknown> | null };

/** `POST .../orders` 응답 → 화면 상태(R11.1 확정, R5.2 401, R10.2·R14.2 409 거절). */
export function applyOrderResult(result: HttpOutcome): LiveOrderState {
  if (result.status === 201) {
    return {
      phase: 'readyToPay',
      orderNo: String(result.body?.orderNo ?? ''),
      totalAmount: Number(result.body?.totalAmount ?? 0),
    };
  }
  if (result.status === 401) {
    return { phase: 'unauthenticated' };
  }
  return { phase: 'rejected', reason: rejectionMessage(result.body?.code as string | undefined) };
}

/** `POST /api/v1/payments/confirm` 응답 → 화면 상태(R11.1 완료, R11.2 실패 사유+재시도). */
export function applyPaymentResult(orderNo: string, totalAmount: number, result: HttpOutcome): LiveOrderState {
  if (result.status === 200) {
    return { phase: 'paid', orderNo };
  }
  if (result.status === 202) {
    return { phase: 'pending', orderNo, totalAmount };
  }
  const reason = String(result.body?.message ?? `결제 승인에 실패했습니다(HTTP ${result.status})`);
  return { phase: 'paymentFailed', orderNo, totalAmount, reason };
}
