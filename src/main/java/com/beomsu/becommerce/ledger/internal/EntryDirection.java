package com.beomsu.becommerce.ledger.internal;

/** 분개 방향. 금액은 항상 양수이고 부호는 방향으로 표현한다. */
public enum EntryDirection {
    DEBIT,   // 차변
    CREDIT   // 대변
}
