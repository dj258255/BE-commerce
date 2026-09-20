package com.beomsu.becommerce.order.compensation;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보상 태스크 운영 어드민 — 소진(FAILED)된 태스크를 조회하고 수동 재시도한다.
 *
 * <p>자동 재시도를 소진해 {@link CompensationStatus#FAILED}가 된 건은 스케줄러가 더 손대지 않는다.
 * 근본 원인을 고친 뒤 운영이 이 서비스로 태스크를 재무장({@link CompensationTask#reopen()})하고
 * 즉시 한 번 시도한다.
 */
@Service
@RequiredArgsConstructor
public class CompensationAdminService {

    private final CompensationTaskRepository repository;
    private final CompensationExecutor executor;

    /** 상태별 보상 태스크 페이지(운영은 주로 FAILED를 본다). */
    @Transactional(readOnly = true)
    public Page<CompensationTaskView> list(CompensationStatus status, Pageable pageable) {
        return repository.findByStatus(status, pageable)
                .map(t -> new CompensationTaskView(t.getId(), t.getOrderNo(), t.getAmount(),
                        t.getStatus(), t.getRetryCount(), t.getLastError(), t.getNextAttemptAt()));
    }

    /**
     * 태스크를 재무장한 뒤 즉시 1회 시도한다. 성공(DONE)하면 true, 다시 실패(예외)하면 실패를
     * 기록하고 false.
     *
     * <p>일부러 {@code @Transactional}을 붙이지 않는다 — {@link CompensationService#processPending()}과
     * 같은 결. reopen 저장·시도·실패기록을 executor의 per-task 트랜잭션에 위임해, 한 건의 실패
     * (rollback-only)가 이 메서드의 트랜잭션 전체를 오염시키지 않게 한다. {@code repository.saveAndFlush}는
     * 자체 트랜잭션으로 PENDING 재무장을 먼저 커밋(flush 강제)해, 이어지는 executor.attempt가 재무장 상태를 본다.
     */
    public boolean retry(Long id) {
        CompensationTask task = repository.findById(id).orElse(null);
        if (task == null) {
            return false;
        }
        task.reopen();
        repository.saveAndFlush(task);
        try {
            executor.attempt(id);
            return true;
        } catch (Exception e) {
            // 시도 tx는 롤백됐다(task는 PENDING 유지) → 별도 tx로 실패를 기록한다.
            executor.recordFailure(id, e.getMessage());
            return false;
        }
    }
}
