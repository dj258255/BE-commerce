package com.beomsu.becommerce.subscription.internal;

import com.beomsu.becommerce.subscription.internal.SubscriptionView;
import com.beomsu.becommerce.subscription.internal.SubscriptionStatus;
import com.beomsu.becommerce.subscription.internal.SubscriptionService;
import com.beomsu.becommerce.subscription.internal.SubscriptionRepository;
import com.beomsu.becommerce.subscription.internal.SubscriptionException;
import com.beomsu.becommerce.subscription.internal.SubscriptionDetailView;
import com.beomsu.becommerce.subscription.internal.Subscription;
import com.beomsu.becommerce.subscription.dunning.DunningAttemptRepository;
import com.beomsu.becommerce.subscription.dunning.DunningAttempt;
import com.beomsu.becommerce.subscription.billing.FakeBillingGateway;
import com.beomsu.becommerce.subscription.billing.BillingResult;
import com.beomsu.becommerce.subscription.billing.BillingKeyRepository;
import com.beomsu.becommerce.subscription.billing.BillingKey;
import com.beomsu.becommerce.shared.crypto.BlindIndexer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class SubscriptionServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 5);

    // 실제 BlindIndexer(순수 HMAC — 부트 없음). 빌링키 인덱스 계산/조회 위임을 실물로 검증한다.
    private static final BlindIndexer BLIND_INDEXER = new BlindIndexer("blind-index-test-secret-000000000");

    private FakeBillingGateway gateway;
    private SubscriptionRepository subscriptionRepository;
    private BillingKeyRepository billingKeyRepository;
    private DunningAttemptRepository dunningAttemptRepository;
    private SubscriptionService service;

    @BeforeEach
    void setUp() {
        gateway = new FakeBillingGateway();
        subscriptionRepository = mock(SubscriptionRepository.class);
        billingKeyRepository = mock(BillingKeyRepository.class);
        dunningAttemptRepository = mock(DunningAttemptRepository.class);
        service = new SubscriptionService(gateway, subscriptionRepository, billingKeyRepository,
                dunningAttemptRepository, BLIND_INDEXER);
    }

    private Subscription activeSub() {
        return Subscription.create(1L, "billing-1", 10_000,
                TODAY.minusMonths(1), TODAY); // 오늘이 청구일
    }

    private void givenBillingTargets(Subscription... subs) {
        when(subscriptionRepository.findByStatusInAndNextBillingDateLessThanEqual(any(), any()))
                .thenReturn(List.of(subs));
    }

    private DunningAttempt capturedDunning() {
        ArgumentCaptor<DunningAttempt> captor = ArgumentCaptor.forClass(DunningAttempt.class);
        verify(dunningAttemptRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("(1) SUCCESS → renew + nextBillingDate 한 달 뒤로 갱신, SUCCESS 이력 기록")
    void successRenews() {
        Subscription sub = activeSub();
        givenBillingTargets(sub);
        when(dunningAttemptRepository.countBySubscriptionIdAndResult(any(), any())).thenReturn(0);
        gateway.setNextResult(BillingResult.SUCCESS);

        int processed = service.runBillingCycle(TODAY);

        assertThat(processed).isEqualTo(1);
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getNextBillingDate()).isEqualTo(TODAY.plusMonths(1));
        // dunning 상태 전이(renew)가 명시 saveAndFlush로 영속된다(OSIV off에서 dirty-checking 자동 flush에 의존하지 않음).
        verify(subscriptionRepository).saveAndFlush(sub);
        assertThat(capturedDunning().getResult()).isEqualTo(BillingResult.SUCCESS);
        assertThat(capturedDunning().getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("(2) SOFT_DECLINE 첫 실패 → IN_GRACE_PERIOD, 다음 날 재시도(D+1) 기록")
    void softDeclineFirstEntersGrace() {
        Subscription sub = activeSub();
        givenBillingTargets(sub);
        when(dunningAttemptRepository.countBySubscriptionIdAndResult(any(), any())).thenReturn(0);
        gateway.setNextResult(BillingResult.SOFT_DECLINE);

        service.runBillingCycle(TODAY);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.IN_GRACE_PERIOD);
        assertThat(sub.getNextBillingDate()).isEqualTo(TODAY); // 유예 중 청구일 불변
        DunningAttempt dunning = capturedDunning();
        assertThat(dunning.getResult()).isEqualTo(BillingResult.SOFT_DECLINE);
        assertThat(dunning.getNextRetryAt()).isEqualTo(TODAY.plusDays(1));
        assertThat(dunning.getAttemptNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("(2-1) 두 번째 SOFT_DECLINE 은 사흘 뒤로 벌린다(D+3)")
    void softDeclineSecondBacksOffFurther() {
        Subscription sub = activeSub();
        sub.enterGrace();                       // 첫 실패로 이미 유예 중
        givenBillingTargets(sub);
        when(dunningAttemptRepository.countBySubscriptionIdAndResult(any(), any())).thenReturn(1);
        gateway.setNextResult(BillingResult.SOFT_DECLINE);

        service.runBillingCycle(TODAY);

        DunningAttempt dunning = capturedDunning();
        assertThat(dunning.getAttemptNo()).isEqualTo(2);
        assertThat(dunning.getNextRetryAt())
                .as("한도 초과와 잔액 부족은 시간이 지나야 풀린다. 같은 간격으로 두드리면 같은 이유로 또 거절된다")
                .isEqualTo(TODAY.plusDays(3));
    }

    @Test
    @DisplayName("(3) HARD_DECLINE → 즉시 ON_HOLD, 재시도 스케줄 없음(nextRetryAt=null)")
    void hardDeclineImmediateHold() {
        Subscription sub = activeSub();
        givenBillingTargets(sub);
        when(dunningAttemptRepository.countBySubscriptionIdAndResult(any(), any())).thenReturn(0);
        gateway.setNextResult(BillingResult.HARD_DECLINE);

        service.runBillingCycle(TODAY);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ON_HOLD);
        DunningAttempt dunning = capturedDunning();
        assertThat(dunning.getResult()).isEqualTo(BillingResult.HARD_DECLINE);
        assertThat(dunning.getNextRetryAt()).isNull(); // 재시도 예약 없음
    }

    @Test
    @DisplayName("(4) SOFT_DECLINE 재시도 소진(3회째 실패) → ON_HOLD, 재시도 없음")
    void softDeclineExhaustedHolds() {
        Subscription sub = activeSub();
        sub.enterGrace(); // 이미 유예 중(앞선 실패들로 진입)
        givenBillingTargets(sub);
        // 앞서 2번 soft decline → 이번이 3번째 = MAX_ATTEMPTS 도달
        when(dunningAttemptRepository.countBySubscriptionIdAndResult(any(), any())).thenReturn(2);
        gateway.setNextResult(BillingResult.SOFT_DECLINE);

        service.runBillingCycle(TODAY);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ON_HOLD);
        DunningAttempt dunning = capturedDunning();
        assertThat(dunning.getResult()).isEqualTo(BillingResult.SOFT_DECLINE);
        assertThat(dunning.getNextRetryAt()).isNull();
        assertThat(dunning.getAttemptNo()).isEqualTo(3);
    }

    @Test
    @DisplayName("SUCCESS: 유예 중이던 구독은 recover 후 ACTIVE로 복귀한다")
    void successRecoversFromGrace() {
        Subscription sub = activeSub();
        sub.enterGrace();
        givenBillingTargets(sub);
        when(dunningAttemptRepository.countBySubscriptionIdAndResult(any(), any())).thenReturn(1);
        gateway.setNextResult(BillingResult.SUCCESS);

        service.runBillingCycle(TODAY);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getNextBillingDate()).isEqualTo(TODAY.plusMonths(1));
    }

    @Test
    @DisplayName("subscribe: 빌링키를 저장하고 첫 청구일을 한 달 뒤로 잡은 ACTIVE 구독을 만든다")
    void subscribeCreatesActive() {
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Subscription sub = service.subscribe(1L, "billing-1", 10_000, TODAY);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getNextBillingDate()).isEqualTo(TODAY.plusMonths(1));

        ArgumentCaptor<BillingKey> captor = ArgumentCaptor.forClass(BillingKey.class);
        verify(billingKeyRepository).save(captor.capture());
        assertThat(captor.getValue().getBillingKey()).isEqualTo("billing-1");
        // customerKey는 UUID(무작위) — 예측 가능한 값이 아니어야 한다
        assertThat(captor.getValue().getCustomerKey()).hasSize(36);
        // 빌링키는 암호화 저장되므로, 저장 시 블라인드 인덱스를 함께 계산해 넣어야 조회·유니크가 산다.
        assertThat(captor.getValue().getBillingKeyIndex()).isEqualTo(BLIND_INDEXER.index("billing-1"));
    }

    @Test
    @DisplayName("findByBillingKey: 원문을 블라인드 인덱스로 해시해 findByBillingKeyIndex로 위임 조회한다")
    void findByBillingKeyDelegatesToIndex() {
        String raw = "billing-1";
        String index = BLIND_INDEXER.index(raw);
        BillingKey stored = BillingKey.of(raw, index, "cust-uuid", 1L);
        when(billingKeyRepository.findByBillingKeyIndex(index)).thenReturn(Optional.of(stored));

        Optional<BillingKey> found = service.findByBillingKey(raw);

        // 원문이 아니라 결정적 인덱스로 조회해야 한다(암호문은 비결정적이라 값 조회 불가).
        verify(billingKeyRepository).findByBillingKeyIndex(index);
        assertThat(found).containsSame(stored);
    }

    @Test
    @DisplayName("changePlan: proration 정산액을 반환하고 planAmount를 갱신한다")
    void changePlanProrates() {
        Subscription sub = Subscription.create(1L, "billing-1", 10_000,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        when(subscriptionRepository.findById(anyLong())).thenReturn(java.util.Optional.of(sub));

        // 15일 남음, 차액 20000 → 10000 추가 청구(업그레이드)
        long proration = service.changePlan(1L, 30_000, LocalDate.of(2026, 1, 16));

        assertThat(proration).isEqualTo(10_000);
        assertThat(sub.getPlanAmount()).isEqualTo(30_000);
        // 플랜 변경 상태 전이가 명시 saveAndFlush로 영속된다(OSIV off에서 dirty-checking 자동 flush에 의존하지 않음).
        verify(subscriptionRepository).saveAndFlush(sub);
    }

    // --- 외부 표면(사용자 API) ---

    private Subscription ownedSub(long userId, long id) {
        Subscription sub = Subscription.create(userId, "billing-1", 10_000, TODAY.minusMonths(1), TODAY);
        org.springframework.test.util.ReflectionTestUtils.setField(sub, "id", id);
        when(subscriptionRepository.findById(id)).thenReturn(Optional.of(sub));
        return sub;
    }

    @Test
    @DisplayName("내 구독 목록 조회: userId 소유 구독을 뷰로 매핑")
    void subscriptionsOfMapsToViews() {
        Subscription sub = Subscription.create(1L, "billing-1", 10_000, TODAY.minusMonths(1), TODAY);
        when(subscriptionRepository.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(sub));

        List<SubscriptionView> views = service.subscriptionsOf(1L);

        assertThat(views).singleElement().satisfies(v -> {
            assertThat(v.planAmount()).isEqualTo(10_000);
            assertThat(v.status()).isEqualTo("ACTIVE");
        });
    }

    @Test
    @DisplayName("구독 해지: 소유자면 CANCELED 전이 + saveAndFlush")
    void cancelByOwner() {
        Subscription sub = ownedSub(1L, 5L);

        SubscriptionView view = service.cancel(5L, 1L);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(view.status()).isEqualTo("CANCELED");
        verify(subscriptionRepository).saveAndFlush(sub);
    }

    @Test
    @DisplayName("남의 구독 해지 시도: SUBSCRIPTION_FORBIDDEN (IDOR 방지) — 전이·저장 없음")
    void cancelByNonOwnerForbidden() {
        Subscription sub = ownedSub(1L, 5L); // 주인은 userId 1

        assertThatThrownBy(() -> service.cancel(5L, 2L)) // userId 2가 시도
                .isInstanceOf(SubscriptionException.class)
                .satisfies(e -> assertThat(((SubscriptionException) e).code()).isEqualTo("SUBSCRIPTION_FORBIDDEN"));

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE); // 미변경
        verify(subscriptionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("구독 상세: 소유자에게 구독 + 청구 이력을 반환")
    void detailWithHistory() {
        Subscription sub = ownedSub(1L, 5L);
        when(dunningAttemptRepository.findBySubscriptionIdOrderByIdAsc(5L)).thenReturn(List.of(
                DunningAttempt.of(5L, 1, BillingResult.SOFT_DECLINE, TODAY.plusDays(3)),
                DunningAttempt.of(5L, 2, BillingResult.SUCCESS, null)));

        SubscriptionDetailView detail = service.detail(5L, 1L);

        assertThat(detail.billingHistory()).hasSize(2);
        assertThat(detail.billingHistory().get(0).result()).isEqualTo("SOFT_DECLINE");
    }

    @Test
    @DisplayName("즉시 청구(billNow): 소유자의 ACTIVE 구독을 즉시 청구 — 성공 시 renew")
    void billNowChargesActive() {
        Subscription sub = ownedSub(1L, 5L);
        gateway.setNextResult(BillingResult.SUCCESS);

        SubscriptionView view = service.billNow(5L, 1L);

        assertThat(view.status()).isEqualTo("ACTIVE");
        // 성공 청구는 다음 청구일을 한 달 뒤로 갱신한다.
        assertThat(sub.getNextBillingDate()).isEqualTo(TODAY.plusMonths(1));
        verify(subscriptionRepository).saveAndFlush(sub);
    }
}
