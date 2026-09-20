package com.beomsu.becommerce.subscription.internal;

import com.beomsu.becommerce.subscription.dunning.DunningAttemptRepository;
import com.beomsu.becommerce.subscription.dunning.DunningAttempt;
import com.beomsu.becommerce.subscription.billing.BillingResult;
import com.beomsu.becommerce.subscription.billing.BillingKeyRepository;
import com.beomsu.becommerce.subscription.billing.BillingKey;
import com.beomsu.becommerce.subscription.billing.BillingGateway;
import com.beomsu.becommerce.subscription.billing.BillingCycle;
import com.beomsu.becommerce.shared.crypto.BlindIndexer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 구독 애플리케이션 서비스 — subscription 모듈의 공개 진입점.
 *
 * <p>정기결제 주기 배치({@link #runBillingCycle})가 핵심이다. 청구 결과(soft/hard decline)에 따라
 * dunning 상태머신을 구동한다:
 * <ul>
 *   <li><b>SUCCESS</b>: 다음 주기 예약(renew). 유예 중이었다면 정상 복귀(recover).</li>
 *   <li><b>SOFT_DECLINE</b>: 첫 실패는 유예기간 진입 + 재시도 예약. 재시도 소진 시 정지.</li>
 *   <li><b>HARD_DECLINE</b>: 재시도 없이 즉시 정지(재시도하면 카드사 평판 하락).</li>
 * </ul>
 */
@Service
@Transactional
@RequiredArgsConstructor
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    /** 청구 주기(1개월). */
    private static final int BILLING_PERIOD_MONTHS = 1;
    /** soft decline 최대 청구 시도 횟수 — 이 횟수째 실패면 재시도 소진으로 정지. */
    private static final int MAX_ATTEMPTS = 3;
    /**
     * soft decline 후 <b>몇 번째 재시도를 며칠 뒤에 걸지</b>. 1회차는 다음 날, 2회차는 사흘 뒤다.
     *
     * <p>고정 간격이었다가 단계별로 벌렸다. soft decline 이 나는 이유가 대개 한도 초과와 잔액
     * 부족인데, <b>그건 시간이 지나야 풀린다.</b> 같은 간격으로 계속 두드리면 같은 이유로 또 거절되고,
     * 그 거절이 가맹점 승인율 기록에 그대로 남는다.
     *
     * <p>국내 결제대행사·카드사는 <b>같은 건에 대한 반복 승인 시도의 횟수와 간격을 계약으로 제한</b>한다.
     * 업계에서 잡는 기준선이 D+1, D+3, D+7 에 3회라 그것을 그대로 따랐다.
     * {@code MAX_ATTEMPTS} 가 3이라 D+7 은 예약되지 않지만, 횟수를 늘릴 때 쓰라고 남겨 둔다.
     */
    private static final int[] RETRY_BACKOFF_DAYS = {1, 3, 7};

    private final BillingGateway billingGateway;
    private final SubscriptionRepository subscriptionRepository;
    private final BillingKeyRepository billingKeyRepository;
    private final DunningAttemptRepository dunningAttemptRepository;
    private final BlindIndexer blindIndexer;

    /**
     * 구독 개시. 빌링키를 저장하고(customerKey는 UUID로 발급 — 예측 불가한 이중 키), ACTIVE 구독을
     * 생성한다. 첫 청구일은 {@code startDate + 1개월}.
     */
    public Subscription subscribe(long userId, String billingKey, long planAmount, LocalDate startDate) {
        // customerKey는 반드시 무작위 UUID — 이메일·자동증가 ID 금지(이중 키 구조 보안).
        String customerKey = UUID.randomUUID().toString();
        // 빌링키는 암호화 저장되므로, 조회·유니크를 위한 블라인드 인덱스를 함께 계산해 넘긴다.
        String billingKeyIndex = blindIndexer.index(billingKey);

        // 이미 폐기된 키로 다시 구독을 만들면, <카드가 죽은 걸 알면서 청구를 예약하는 것>이다.
        // 첫 청구에서 실패하고 dunning 이 그 실패를 재시도한다. 아는 실패는 미리 막는다.
        billingKeyRepository.findByBillingKeyIndex(billingKeyIndex).ifPresent(existing -> {
            if (!existing.isActive()) {
                throw new SubscriptionException("BILLING_KEY_REVOKED",
                        "폐기된 빌링키로는 구독을 만들 수 없습니다. 결제 수단을 다시 등록해야 합니다.");
            }
        });

        billingKeyRepository.save(BillingKey.of(billingKey, billingKeyIndex, customerKey, userId));

        LocalDate nextBillingDate = BillingCycle.next(startDate, BillingCycle.anchorOf(startDate), BILLING_PERIOD_MONTHS);
        Subscription subscription = Subscription.create(userId, billingKey, planAmount, startDate, nextBillingDate);
        return subscriptionRepository.save(subscription);
    }

    /**
     * 원문 빌링키로 저장된 빌링키를 조회한다. 빌링키 컬럼은 암호화(비결정적)라 값으로 직접 WHERE를
     * 걸 수 없으므로, 원문을 블라인드 인덱스로 해시해 결정적 인덱스 컬럼으로 조회한다.
     */
    @Transactional(readOnly = true)
    public Optional<BillingKey> findByBillingKey(String billingKey) {
        return billingKeyRepository.findByBillingKeyIndex(blindIndexer.index(billingKey));
    }

    /**
     * 결제 주기 배치. 다음 청구일이 today 이하인 ACTIVE/IN_GRACE_PERIOD 구독을 청구한다.
     *
     * @return 처리한 구독 건수
     */
    public int runBillingCycle(LocalDate today) {
        List<Subscription> targets = subscriptionRepository.findByStatusInAndNextBillingDateLessThanEqual(
                List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.IN_GRACE_PERIOD), today);

        for (Subscription subscription : targets) {
            bill(subscription, today);
        }
        return targets.size();
    }

    /** 구독 1건 청구 — 배치와 데모 트리거가 공유한다. dunning 상태 전이를 명시 saveAndFlush로 확정. */
    private void bill(Subscription subscription, LocalDate today) {
        BillingResult result = billingGateway.charge(subscription.getBillingKey(), subscription.getPlanAmount());
        switch (result) {
            case SUCCESS -> handleSuccess(subscription);
            case SOFT_DECLINE -> handleSoftDecline(subscription, today);
            case HARD_DECLINE -> handleHardDecline(subscription);
        }
        // dirty-check 자동 flush는 readOnly 조회로 세션 FlushMode가 MANUAL이거나 detached 엔티티인 경우
        // 신뢰할 수 없어(pay-26 교훈) 전이를 saveAndFlush로 확정한다.
        subscriptionRepository.saveAndFlush(subscription);
    }

    // --- 외부 표면(사용자 API) ---

    /** 구독 개시(컨트롤러용) — 오늘 시작으로 구독을 만들고 뷰를 반환한다(엔티티 미노출). */
    @Transactional
    public SubscriptionView createSubscription(long userId, String billingKey, long planAmount) {
        return SubscriptionView.from(subscribe(userId, billingKey, planAmount, LocalDate.now()));
    }

    /** 내 구독 목록. */
    @Transactional(readOnly = true)
    public List<SubscriptionView> subscriptionsOf(long userId) {
        return subscriptionRepository.findByUserIdOrderByIdDesc(userId).stream()
                .map(SubscriptionView::from).toList();
    }

    /** 구독 상세 + 청구 이력. 소유자만 조회 가능(IDOR 방지). */
    @Transactional(readOnly = true)
    public SubscriptionDetailView detail(Long subscriptionId, long userId) {
        Subscription s = requireOwned(subscriptionId, userId);
        return SubscriptionDetailView.of(s, dunningAttemptRepository.findBySubscriptionIdOrderByIdAsc(subscriptionId));
    }

    /** 구독 해지(ACTIVE/유예/정지 → CANCELED). 소유자만. */
    @Transactional
    public SubscriptionView cancel(Long subscriptionId, long userId) {
        Subscription s = requireOwned(subscriptionId, userId);
        s.cancel(); // 도메인 가드: CANCELED로의 허용 전이만
        subscriptionRepository.saveAndFlush(s);
        // 해지했으면 결제 수단도 놓아준다. 안 놓으면 <해지한 고객의 카드 대체물을 계속 보관>한다.
        // 다만 같은 키를 쓰는 다른 구독이 남아 있으면 폐기하지 않는다 — 남의 결제 수단을 지우는 셈이다.
        if (!subscriptionRepository.existsByBillingKeyAndStatusIn(s.getBillingKey(),
                List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.IN_GRACE_PERIOD,
                        SubscriptionStatus.ON_HOLD))) {
            revokeBillingKey(s.getBillingKey(), "구독 해지");
        }
        return SubscriptionView.from(s);
    }

    /** 데모/운영 트리거 — 이 구독을 지금 즉시 청구한다(날짜 무관). 소유자만. */
    @Transactional
    public SubscriptionView billNow(Long subscriptionId, long userId) {
        Subscription s = requireOwned(subscriptionId, userId);
        if (s.getStatus() != SubscriptionStatus.ACTIVE
                && s.getStatus() != SubscriptionStatus.IN_GRACE_PERIOD) {
            throw SubscriptionException.notActive(s.getStatus());
        }
        bill(s, LocalDate.now());
        return SubscriptionView.from(s);
    }

    /** 소유권 검증 — 남의 구독 조회·변경(IDOR)을 막는다. 무엇보다 먼저. */
    private Subscription requireOwned(Long subscriptionId, long userId) {
        Subscription s = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> SubscriptionException.notFound(subscriptionId));
        if (s.getUserId() != userId) {
            throw new SubscriptionException("SUBSCRIPTION_FORBIDDEN", "본인의 구독만 접근할 수 있습니다.");
        }
        return s;
    }

    private void handleSuccess(Subscription subscription) {
        // 유예기간 중 회수 성공 → 정상 복귀 후 다음 주기 예약.
        if (subscription.getStatus() == SubscriptionStatus.IN_GRACE_PERIOD) {
            subscription.recover();
        }
        // 직전 청구일이 아니라 <앵커>에서 계산한다. 직전 값에서 더하면 2월에 당겨진 날이 영영 돌아오지 않는다.
        LocalDate nextBillingDate = BillingCycle.next(
                subscription.getNextBillingDate(), subscription.getAnchorDay(), BILLING_PERIOD_MONTHS);
        subscription.renew(nextBillingDate);

        int attemptNo = priorSoftDeclines(subscription) + 1;
        dunningAttemptRepository.save(
                DunningAttempt.of(subscription.getId(), attemptNo, BillingResult.SUCCESS, null));
    }

    private void handleSoftDecline(Subscription subscription, LocalDate today) {
        int attemptNo = priorSoftDeclines(subscription) + 1;
        LocalDate nextRetryAt;
        if (attemptNo >= MAX_ATTEMPTS) {
            // 재시도 소진 → 정지. 예약된 재시도 없음.
            if (subscription.getStatus() == SubscriptionStatus.ACTIVE) {
                subscription.enterGrace(); // 방어적: ACTIVE에서 곧장 소진 시 유예 경유
            }
            subscription.hold();
            nextRetryAt = null;
        } else {
            // 첫 실패면 유예기간 진입, 이후엔 유예 유지. 다음 재시도 예약.
            if (subscription.getStatus() == SubscriptionStatus.ACTIVE) {
                subscription.enterGrace();
            }
            nextRetryAt = today.plusDays(retryBackoffDays(attemptNo));
        }
        dunningAttemptRepository.save(
                DunningAttempt.of(subscription.getId(), attemptNo, BillingResult.SOFT_DECLINE, nextRetryAt));
    }

    /**
     * {@code attemptNo} 번째 실패 뒤 며칠을 기다릴지. 표를 넘어가면 마지막 값을 쓴다.
     *
     * <p>표 밖으로 나가면 예외를 던지지 않는다. {@code MAX_ATTEMPTS} 를 올리는 것은 설정 변경에
     * 가까운데, 그때 <b>결제 스케줄러가 예외로 멈추면 그 회차 청구가 통째로 빠진다.</b>
     */
    private static int retryBackoffDays(int attemptNo) {
        int idx = Math.min(Math.max(attemptNo, 1), RETRY_BACKOFF_DAYS.length) - 1;
        return RETRY_BACKOFF_DAYS[idx];
    }

    private void handleHardDecline(Subscription subscription) {
        // hard decline(도난/무효 카드): 재시도하면 카드사에서 가맹점 평판이 하락하므로 재시도 없이 즉시 정지.
        subscription.hold();
        // 카드가 죽었다. 구독만 세우고 키를 살려 두면 <같은 죽은 키로 다음 구독이 또 실패한다>.
        // 이 키는 어느 구독에서도 못 쓴다 — 남은 구독이 있는지 보지 않고 바로 폐기하는 이유다.
        revokeBillingKey(subscription.getBillingKey(), "카드 거절(HARD_DECLINE)");
        int attemptNo = priorSoftDeclines(subscription) + 1;
        dunningAttemptRepository.save(
                DunningAttempt.of(subscription.getId(), attemptNo, BillingResult.HARD_DECLINE, null));
    }

    /**
     * 빌링키를 폐기한다. <b>키를 못 찾아도 조용히 넘어간다</b> — 구독 해지는 이미 끝났고,
     * 여기서 예외를 던지면 <b>끝난 해지가 롤백된다</b>. 폐기 실패는 해지 실패보다 가볍다.
     */
    private void revokeBillingKey(String billingKey, String reason) {
        billingKeyRepository.findByBillingKeyIndex(blindIndexer.index(billingKey))
                .ifPresent(key -> {
                    key.revoke(reason);
                    billingKeyRepository.saveAndFlush(key);
                    log.info("빌링키 폐기 reason={}", reason);
                });
    }

    private int priorSoftDeclines(Subscription subscription) {
        return dunningAttemptRepository.countBySubscriptionIdAndResult(
                subscription.getId(), BillingResult.SOFT_DECLINE);
    }

    /**
     * 플랜 변경 — 일할계산(proration). 남은 기간 비율로 신·구 차액을 정산한다.
     *
     * @return 정산액(원). 양수=추가 청구(업그레이드), 음수=크레딧(다운그레이드)
     */
    public long changePlan(Long subscriptionId, long newAmount, LocalDate changeDate) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> SubscriptionException.notFound(subscriptionId));

        long oldAmount = subscription.getPlanAmount();
        long proration = ProrationCalculator.prorate(
                oldAmount, newAmount,
                subscription.getCurrentPeriodStart(), subscription.getNextBillingDate(), changeDate);

        subscription.changePlan(newAmount);
        // 상태 전이(플랜 변경)를 saveAndFlush로 명시 영속한다. dirty-check 자동 flush는 readOnly 조회로
        // 세션 FlushMode가 MANUAL이거나 detached 엔티티인 경우 신뢰할 수 없어(pay-26 교훈) 확정을 강제한다.
        subscriptionRepository.saveAndFlush(subscription);
        log.debug("플랜 변경: sub={} {}원 → {}원, proration={}원",
                subscriptionId, oldAmount, newAmount, proration);
        return proration;
    }
}
