package com.beomsu.becommerce.settlement.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SettlementItem} 의 {@code @Version} 이 lost update 를 실제로 막는지 본다.
 *
 * <p>배치의 {@link SettlementItem#markSettled}(CONFIRMED→SETTLED, {@code settle})와 취소 리스너의
 * {@link SettlementItem#cancel}(CONFIRMED→CANCELED, {@code reflectCancellation})이 <b>같은 CONFIRMED
 * 항목</b>을 동시에 쓰면 늦은 쪽의 변경이 조용히 사라진다. 정산은 돈이라 그 유실이 이중 지급이거나
 * 누락이 된다. 모킹한 리포지토리로는 낙관적 락이 관여할 여지가 없어(영속성 컨텍스트가 없다), 실제 JPA
 * 영속성 컨텍스트 둘로 재현한다. MySQL 마이그레이션 대신 {@code create-drop} 으로 스키마만 세운다 —
 * 보는 것은 스키마가 아니라 버전 충돌이다.
 */
@DataJpaTest(showSql = false, properties = {
        // MySQL 마이그레이션은 H2에서 안 돈다. 이 테스트는 스키마가 아니라 낙관적 락만 본다.
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ContextConfiguration(classes = SettlementItemOptimisticLockTest.TestApp.class)
// 테스트가 트랜잭션을 잡으면 각 리포지토리 호출이 한 트랜잭션에 참여해 <커밋>을 못 본다.
// 보려는 것은 "따로 커밋된 두 갱신의 충돌"이라 테스트는 트랜잭션 밖에 둔다.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SettlementItemOptimisticLockTest {

    @Autowired
    SettlementItemRepository itemRepository;

    @Test
    @DisplayName("배치 SETTLED 와 취소 반영이 같은 CONFIRMED 항목을 동시에 쓰면 늦은 쪽이 OptimisticLockingFailure 로 실패한다")
    void settleAndCancellationConflictOnSameConfirmedItem() {
        // 승인·구매확정까지 끝난 CONFIRMED 항목 하나를 만든다.
        SettlementItem saved = itemRepository.save(
                SettlementItem.of(1L, "ord-1", 10_000, LocalDate.of(2026, 7, 5), 1L));
        saved.confirm(LocalDate.of(2026, 7, 5));
        itemRepository.saveAndFlush(saved);
        Long id = saved.getId();

        // 독립된 두 번의 조회 = CONFIRMED 상태의 detached 사본 둘, 각자 같은 version 을 들고 있다.
        SettlementItem batchView = itemRepository.findById(id).orElseThrow();
        SettlementItem cancelView = itemRepository.findById(id).orElseThrow();
        batchView.markSettled(500L);   // 배치가 CONFIRMED → SETTLED 로 밀어붙이는 변경
        cancelView.cancel();           // 취소 리스너가 CONFIRMED → CANCELED 로 미는 변경

        itemRepository.saveAndFlush(batchView);   // 배치가 먼저 커밋 → version +1

        assertThatThrownBy(() -> itemRepository.saveAndFlush(cancelView))
                .as("늦게 커밋하는 취소 반영이 실패해야 정산 결과가 조용히 덮이지 않는다")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = SettlementItem.class)
    static class TestApp {
    }
}
