package com.beomsu.becommerce.payment.va;

import com.beomsu.becommerce.payment.PaymentException;
import com.beomsu.becommerce.payment.pg.PgClient;
import com.beomsu.becommerce.payment.pg.PgQueryResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 가상계좌 발급·입금 확정·만료 배치·역전이 처리.
 *
 * <p>공통 원칙(Phase 3): <b>웹훅 페이로드를 믿지 않고 PG 조회 API로 재검증</b>한다.
 * 입금 웹훅이 두 번 오거나(멱등), 순서가 뒤집히거나(역전이), 아예 안 와도(만료 배치) 견디는 것이 목표.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class VirtualAccountService {

    private static final Logger log = LoggerFactory.getLogger(VirtualAccountService.class);

    /** 데모용 은행 코드(토스페이먼츠 "20" = 우리은행). 실서비스는 PG 응답의 vAccount를 저장한다. */
    private static final String DEMO_BANK_CODE = "20";

    private final VirtualAccountRepository repository;
    private final PgClient pgClient;

    /**
     * 가상계좌 발급. bankCode/accountNumber는 데모 값이며 dueDate는 now + dueHours로 잡는다.
     * 실서비스에서는 PG 발급 API 응답의 계좌 정보·입금기한을 그대로 저장한다.
     */
    public VirtualAccount issue(String orderNo, String paymentKey, long amount, int dueHours) {
        String accountNumber = generateAccountNumber();
        Instant dueDate = Instant.now().plusSeconds((long) dueHours * 3600);
        VirtualAccount va = VirtualAccount.issue(
                orderNo, paymentKey, DEMO_BANK_CODE, accountNumber, amount, dueDate);
        return repository.save(va);
    }

    /**
     * 입금 웹훅 수신 처리. <b>웹훅 페이로드를 믿지 않고 PgClient.query로 재검증</b>한다.
     * PG가 APPROVED면 입금 확정(DONE), 아니면 무시(로깅)해 위조·조기 웹훅에 속지 않는다(멱등).
     */
    public void confirmDeposit(String paymentKey) {
        VirtualAccount va = requireByPaymentKey(paymentKey);
        PgQueryResult pg = pgClient.query(paymentKey);
        if (pg.isApproved()) {
            va.confirmDeposit();
            // 상태 전이(DONE)를 saveAndFlush로 명시 영속한다. dirty-check 자동 flush는 readOnly 조회로
            // 세션 FlushMode가 MANUAL이거나 detached 엔티티인 경우 신뢰할 수 없어(pay-26 교훈) 확정을 강제한다.
            repository.saveAndFlush(va);
        } else {
            // 조회로 재검증했더니 아직 승인 아님 — 위조/조기 웹훅으로 보고 상태를 바꾸지 않는다.
            log.warn("입금 웹훅 무시: PG 미승인 paymentKey={} pgStatus={}", paymentKey, pg.status());
        }
    }

    /**
     * 만료 배치. <b>EXPIRED는 웹훅이 안 와서 배치로 감지하고, 만료-입금 레이스는 조건부/조회로 해소한다.</b>
     *
     * <p>WAITING이며 입금기한이 지난 가상계좌를 스캔해 PgClient.query로 재확인한다.
     * <ul>
     *   <li>APPROVED(입금이 늦게 도착 = 만료-입금 레이스) → 만료시키지 않고 입금 확정(DONE)</li>
     *   <li>그 외 → 만료(EXPIRED)</li>
     * </ul>
     * 건별 예외를 격리해 한 건 실패가 배치 전체를 멈추지 않게 하고, 처리한 건수를 반환한다.
     */
    public int expireOverdue(Instant now) {
        List<VirtualAccount> targets =
                repository.findByStatusAndDueDateBefore(VaStatus.WAITING_FOR_DEPOSIT, now, chunk());

        int processed = 0;
        for (VirtualAccount va : targets) {
            try {
                PgQueryResult pg = pgClient.query(va.getPaymentKey());
                if (pg.isApproved()) {
                    // 만료-입금 레이스: 만료 직전에 입금이 도착했다 — 만료 대신 입금 확정
                    va.confirmDeposit();
                } else {
                    va.expire();
                }
                // 상태 전이(DONE/EXPIRED)를 saveAndFlush로 명시 영속한다. dirty-check 자동 flush는 readOnly
                // 조회로 세션 FlushMode가 MANUAL이거나 detached 엔티티인 경우 신뢰할 수 없어(pay-26 교훈) 확정을 강제한다.
                repository.saveAndFlush(va);
                processed++;
            } catch (Exception e) {
                // 한 건 실패가 배치 전체를 멈추지 않게 한다. 다음 주기에 다시 시도된다.
                log.warn("가상계좌 만료 처리 실패 vaId={} : {}", va.getId(), e.getMessage());
            }
        }
        return processed;
    }

    /**
     * 은행 지연 통보 수신 처리. DONE 통보가 먼저 온 뒤 은행이 입금 실패를 되돌리는 통보를 보내면,
     * DONE → WAITING_FOR_DEPOSIT 역전이로 되돌린다. 후속 보상(알림·포인트 원복)은 호출측 책임.
     */
    public void handleDepositReversal(String paymentKey, String reason) {
        VirtualAccount va = requireByPaymentKey(paymentKey);
        va.reverseDeposit(reason);
        // 상태 전이(역전이: DONE → WAITING_FOR_DEPOSIT)를 saveAndFlush로 명시 영속한다. dirty-check 자동
        // flush는 readOnly 조회로 세션 FlushMode가 MANUAL이거나 detached 엔티티인 경우 신뢰할 수 없어(pay-26 교훈) 확정을 강제한다.
        repository.saveAndFlush(va);
    }

    private VirtualAccount requireByPaymentKey(String paymentKey) {
        return repository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new PaymentException("VIRTUAL_ACCOUNT_NOT_FOUND",
                        "가상계좌를 찾을 수 없습니다: " + paymentKey));
    }

    private String generateAccountNumber() {
        return UUID.randomUUID().toString().replaceAll("-", "").substring(0, 14);
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
