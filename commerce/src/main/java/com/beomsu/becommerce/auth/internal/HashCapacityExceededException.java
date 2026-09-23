package com.beomsu.becommerce.auth.internal;

import com.beomsu.becommerce.shared.DomainException;

/**
 * 비밀번호 해싱 동시 실행 상한을 넘겼다 — 429로 즉시 거절한다.
 *
 * <p>기다리게 하지 않는 이유: 대기시키면 스레드가 묶이고, 그 스레드가 다시 메모리를 잡는다.
 * 이 경로에서 지연은 방어가 아니라 증폭이다.
 */
public class HashCapacityExceededException extends DomainException {

    public HashCapacityExceededException() {
        super("AUTH_HASH_CAPACITY", "로그인 요청이 몰려 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }
}
