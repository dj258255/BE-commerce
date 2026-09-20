package com.beomsu.becommerce.fraud.internal;

import com.beomsu.becommerce.fraud.internal.FraudService;
import com.beomsu.becommerce.fraud.internal.FraudResult;
import com.beomsu.becommerce.fraud.internal.FraudCheckRequest;
import com.beomsu.becommerce.fraud.internal.FdsDecision;
import com.beomsu.becommerce.fraud.internal.CardBlocklist;
import com.beomsu.becommerce.fraud.velocity.VelocityCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

class FraudServiceTest {

    private VelocityCounter velocityCounter;
    private CardBlocklist cardBlocklist;
    private FraudService service;

    @BeforeEach
    void setUp() {
        velocityCounter = mock(VelocityCounter.class);
        cardBlocklist = mock(CardBlocklist.class);
        service = new FraudService(velocityCounter, cardBlocklist);
        // @Value 기본값을 테스트에서 명시적으로 설정 (무배포 조정 파라미터)
        ReflectionTestUtils.setField(service, "velocityThreshold", 5);
        ReflectionTestUtils.setField(service, "velocityWeight", 40);
        ReflectionTestUtils.setField(service, "amountThreshold", 1_000_000L);
        ReflectionTestUtils.setField(service, "amountWeight", 30);
        ReflectionTestUtils.setField(service, "blacklistWeight", 100);
        ReflectionTestUtils.setField(service, "blockThreshold", 100);
        ReflectionTestUtils.setField(service, "reviewThreshold", 60);
        ReflectionTestUtils.setField(service, "challengeThreshold", 40);
        ReflectionTestUtils.setField(service, "deviceThreshold", 8);
        ReflectionTestUtils.setField(service, "deviceWeight", 25);
        ReflectionTestUtils.setField(service, "ipThreshold", 15);
        ReflectionTestUtils.setField(service, "ipWeight", 20);
        ReflectionTestUtils.setField(service, "longInstallmentMonths", 6);
        ReflectionTestUtils.setField(service, "installmentAmountThreshold", 1_000_000L);
        ReflectionTestUtils.setField(service, "installmentWeight", 20);
    }

    private FraudCheckRequest req(long amount) {
        return new FraudCheckRequest(1L, "card-1", "1.2.3.4", "device-1", amount);
    }

    @Test
    @DisplayName("정상: 낮은 velocity + 소액 → ALLOW (점수 0)")
    void allow() {
        when(velocityCounter.recordAndCount(anyString())).thenReturn(1);

        FraudResult r = service.evaluate(req(10_000));

        assertThat(r.decision()).isEqualTo(FdsDecision.ALLOW);
        assertThat(r.score()).isZero();
    }

    @Test
    @DisplayName("velocity 초과(6>5) → +40 → CHALLENGE")
    void velocityExceededChallenges() {
        when(velocityCounter.recordAndCount(anyString())).thenReturn(6);

        FraudResult r = service.evaluate(req(10_000));

        assertThat(r.decision()).isEqualTo(FdsDecision.CHALLENGE);
        assertThat(r.reasons()).anyMatch(s -> s.startsWith("VELOCITY_EXCEEDED"));
    }

    @Test
    @DisplayName("고액만(+30)은 임계 미만 → ALLOW")
    void highAmountAloneAllows() {
        when(velocityCounter.recordAndCount(anyString())).thenReturn(1);

        FraudResult r = service.evaluate(req(2_000_000));

        assertThat(r.score()).isEqualTo(30);
        assertThat(r.decision()).isEqualTo(FdsDecision.ALLOW);
    }

    @Test
    @DisplayName("velocity + 고액(40+30=70) → REVIEW")
    void velocityPlusAmountReviews() {
        when(velocityCounter.recordAndCount(anyString())).thenReturn(6);

        FraudResult r = service.evaluate(req(2_000_000));

        assertThat(r.score()).isEqualTo(70);
        assertThat(r.decision()).isEqualTo(FdsDecision.REVIEW);
    }

    private FraudCheckRequest req(long amount, int installmentMonths) {
        return new FraudCheckRequest(1L, "card-1", "1.2.3.4", "device-1", amount, installmentMonths);
    }

    @Test
    @DisplayName("고액 장기 할부는 점수만 얹는다 — 차단이 아니라 사람이 볼 이유를 만든다")
    void longInstallmentOnHighAmountScoresButDoesNotBlock() {
        when(velocityCounter.recordAndCount(anyString())).thenReturn(1);

        FraudResult r = service.evaluate(req(4_000_000, 12));

        // 고액(+30) + 장기 할부(+20) = 50. CHALLENGE(40) 는 넘고 REVIEW(60) 에는 못 미친다.
        assertThat(r.score()).isEqualTo(50);
        assertThat(r.reasons()).anyMatch(s -> s.startsWith("LONG_INSTALLMENT_HIGH_AMOUNT"));
        assertThat(r.decision()).isEqualTo(FdsDecision.CHALLENGE);
    }

    @Test
    @DisplayName("소액 장기 할부는 신호가 아니다 — 할부 자체는 정상이다")
    void longInstallmentOnSmallAmountIsNotASignal() {
        when(velocityCounter.recordAndCount(anyString())).thenReturn(1);

        FraudResult r = service.evaluate(req(300_000, 12));

        assertThat(r.score()).isZero();
        assertThat(r.reasons()).noneMatch(s -> s.startsWith("LONG_INSTALLMENT"));
    }

    @Test
    @DisplayName("고액 일시불도 할부 신호가 아니다")
    void highAmountLumpSumIsNotAnInstallmentSignal() {
        when(velocityCounter.recordAndCount(anyString())).thenReturn(1);

        FraudResult r = service.evaluate(req(4_000_000, 0));

        assertThat(r.score()).isEqualTo(30);      // 고액만
        assertThat(r.reasons()).noneMatch(s -> s.startsWith("LONG_INSTALLMENT"));
    }

    @Test
    @DisplayName("카드를 바꿔 가며 같은 기기로 두드리면 카드 기준으로는 안 보인다")
    void deviceVelocityCatchesCardRotation() {
        // 카드는 매번 새것이라 카드 카운터는 낮다. 기기 카운터만 올라간다.
        when(velocityCounter.recordAndCount(startsWith("card:"))).thenReturn(1);
        when(velocityCounter.recordAndCount(startsWith("device:"))).thenReturn(9);
        when(velocityCounter.recordAndCount(startsWith("ip:"))).thenReturn(3);

        FraudResult r = service.evaluate(req(10_000));

        assertThat(r.score()).isEqualTo(25);
        assertThat(r.reasons()).anyMatch(s -> s.startsWith("DEVICE_VELOCITY_EXCEEDED"));
        // 차단이 아니라 <사람이 들여다볼 이유>를 만드는 층이다.
        assertThat(r.decision()).isEqualTo(FdsDecision.ALLOW);
    }

    @Test
    @DisplayName("IP 임계는 가장 느슨하다 — 공용망 뒤에서 남남이 공유하기 때문")
    void ipThresholdIsLoosest() {
        when(velocityCounter.recordAndCount(startsWith("card:"))).thenReturn(1);
        when(velocityCounter.recordAndCount(startsWith("device:"))).thenReturn(1);
        when(velocityCounter.recordAndCount(startsWith("ip:"))).thenReturn(10);

        // 기기 임계(8)는 넘겼을 값인데 IP 임계(15)는 안 넘는다.
        assertThat(service.evaluate(req(10_000)).score()).isZero();
    }

    @Test
    @DisplayName("기기·IP가 없으면 세지 않는다 — 없는 값을 한 덩어리로 묶으면 남이 걸린다")
    void nullKeysAreNotCounted() {
        when(velocityCounter.recordAndCount(anyString())).thenReturn(99);

        FraudResult r = service.evaluate(new FraudCheckRequest(1L, "card-1", null, null, 10_000));

        assertThat(r.reasons()).noneMatch(s -> s.startsWith("DEVICE_") || s.startsWith("IP_"));
        verify(velocityCounter, never()).recordAndCount(startsWith("device:"));
        verify(velocityCounter, never()).recordAndCount(startsWith("ip:"));
    }

    @Test
    @DisplayName("블랙리스트 카드(+100) → BLOCK")
    void blacklistBlocks() {
        when(velocityCounter.recordAndCount(anyString())).thenReturn(1);
        service.blacklistCard("card-1");
        // 차단 목록은 이제 인스턴스 사이에 공유된다 — 조회도 그 목록에 묻는다.
        when(cardBlocklist.contains("card-1")).thenReturn(true);

        FraudResult r = service.evaluate(req(10_000));

        assertThat(r.decision()).isEqualTo(FdsDecision.BLOCK);
        assertThat(r.isBlocked()).isTrue();
        assertThat(r.reasons()).contains("BLACKLISTED_CARD");
        verify(cardBlocklist).block("card-1");
    }

    @Test
    @DisplayName("다른 인스턴스가 차단한 카드도 막힌다 — 인메모리 Set이었으면 통과했다")
    void blockedByAnotherInstance() {
        when(velocityCounter.recordAndCount(anyString())).thenReturn(1);
        // 이 인스턴스는 blacklistCard 를 부른 적이 없다. 공유 목록에만 있다.
        when(cardBlocklist.contains("card-1")).thenReturn(true);

        FraudResult r = service.evaluate(req(10_000));

        assertThat(r.decision()).isEqualTo(FdsDecision.BLOCK);
        assertThat(r.reasons()).contains("BLACKLISTED_CARD");
    }
}
