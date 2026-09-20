package com.beomsu.becommerce.seller;

/**
 * 판매자 심사 상태. <b>정산 지급이 이 값을 본다.</b>
 *
 * <p>상태를 넷으로 둔 이유는 <b>"안 본 것"과 "통과"를 구별하려는 것</b>이다.
 * 셋으로 줄여 통과·보류·차단만 두면 아직 심사 안 한 판매자가 통과와 섞인다.
 * 이 시스템이 결제 승인을 승인·거절·미확정 셋으로 두는 것과 같은 이유다.
 */
public enum SellerStatus {
    /** 등록됐고 아직 안 봤다. <b>지급 안 나간다.</b> */
    PENDING_SCREENING,
    /** 통과. 정산 가능 */
    ACTIVE,
    /** 잠재 일치가 있어 사람이 확인 중. <b>지급을 막는다</b> */
    ON_HOLD,
    /** 확정 일치. 거래하지 않는다 */
    BLOCKED;

    /** 이 상태에서 돈을 내보내도 되는가. <b>ACTIVE 만 참이다.</b> */
    public boolean payable() {
        return this == ACTIVE;
    }
}
