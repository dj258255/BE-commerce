package com.beomsu.becommerce.order.compensation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 보상 태스크의 적재·배치 처리 진입점 — order 모듈.
 *
 * <p>적재({@link #enqueueNetworkCancel})는 체크아웃 트랜잭션과 같은 tx에서 호출돼 승인·재고복원과
 * 함께 커밋된다(durable 보장). 배치({@link #processPending})는 <b>트랜잭션 없이</b> 돌면서
 * 태스크마다 {@link CompensationExecutor}의 별도 트랜잭션에 위임한다 — 한 건의 rollback-only가
 * 배치 트랜잭션 전체로 전파되는 것을 원천 차단한다.
 */
@Service
@RequiredArgsConstructor
public class CompensationService {

    private final CompensationTaskRepository repository;
    private final CompensationExecutor executor;

    /** 카드 망취소 보상 태스크를 적재한다. 체크아웃과 같은 트랜잭션에서 호출된다. */
    @Transactional
    public void enqueueNetworkCancel(String orderNo, long amount, String reason) {
        repository.save(CompensationTask.networkCancel(orderNo, amount, reason));
    }

    /**
     * 재시도 도래한 PENDING 태스크를 처리한다. 반환값은 성공 처리 건수.
     *
     * <p>일부러 {@code @Transactional}을 붙이지 않는다 — 태스크별 트랜잭션을 executor가 관리하고,
     * 여기서 트랜잭션을 열면 한 건의 실패(rollback-only)가 배치 전체를 오염시킨다. 한 건 실패가
     * 배치를 멈추지 않도록 per-task try/catch로 격리한다.
     */
    public int processPending() {
        List<CompensationTask> due =
                repository.findByStatusAndNextAttemptAtBefore(CompensationStatus.PENDING, Instant.now(), chunk());
        int success = 0;
        for (CompensationTask task : due) {
            try {
                executor.attempt(task.getId());
                success++;
            } catch (Exception e) {
                // 시도 tx는 롤백됐다(task는 PENDING 유지) → 별도 tx로 실패를 기록한다.
                executor.recordFailure(task.getId(), e.getMessage());
            }
        }
        return success;
    }

    /**
     * 배치 한 번이 읽는 상한. 남은 것은 다음 주기가 가져간다.
     *
     * <p><b>필드에 기본값을 둔다.</b> {@code @Value} 는 스프링이 만들어 줄 때만 채워지는데,
     * 단위 테스트는 이 서비스를 직접 생성한다. 초기값이 없으면 0 이 되어 페이지 크기가
     * 0 이라고 터진다 — 실제로 그렇게 깨졌다.
     */
    @org.springframework.beans.factory.annotation.Value("${app.batch.read-chunk-size:500}")
    private int readChunkSize = 500;

    /**
     * <b>설정이 0 이나 음수여도 배치를 죽이지 않는다.</b> 잘못된 설정 하나로 돈을 다루는
     * 배치가 멈추는 것보다, 기본값으로 도는 편이 낫다.
     */
    private org.springframework.data.domain.Pageable chunk() {
        return org.springframework.data.domain.PageRequest.of(0, readChunkSize > 0 ? readChunkSize : 500);
    }
}
