package com.beomsu.becommerce.wallet.internal;

import org.springframework.data.jpa.repository.JpaRepository;

// 같은 모듈의 API(루트)가 참조하므로 public 이다. Modulith 문서가 짚듯 internal 에 있어도
// public 이면 컴파일러는 막지 못하며, 모듈 밖 접근은 ModularityTests 의 allowedDependencies 가 막는다.
public interface WalletAccountRepository extends JpaRepository<WalletAccount, Long> {
}
