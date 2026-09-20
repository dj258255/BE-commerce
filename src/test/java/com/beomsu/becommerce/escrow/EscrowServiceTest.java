package com.beomsu.becommerce.escrow;

import org.springframework.data.domain.Pageable;

import com.beomsu.becommerce.escrow.internal.EscrowStatus;
import com.beomsu.becommerce.escrow.internal.EscrowHoldView;
import com.beomsu.becommerce.escrow.internal.EscrowHoldRepository;
import com.beomsu.becommerce.escrow.internal.EscrowHold;
import com.beomsu.becommerce.escrow.internal.EscrowException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EscrowServiceTest {

    private EscrowHoldRepository repository;
    private ApplicationEventPublisher events;
    private EscrowService service;

    @BeforeEach
    void setUp() {
        repository = mock(EscrowHoldRepository.class);
        events = mock(ApplicationEventPublisher.class);
        service = new EscrowService(repository, events);
        // @Value 기본값을 테스트에서 명시 설정 (보류 기간 7일)
        ReflectionTestUtils.setField(service, "holdPeriodDays", 7L);
    }

    private EscrowHold heldHold(String orderNo, long amount) {
        Instant now = Instant.now();
        return EscrowHold.hold(orderNo, amount, now, now.plus(7, ChronoUnit.DAYS));
    }

    // ---- hold ----

    @Test
    @DisplayName("hold: 신규 주문이면 autoReleaseAt = 승인시각+7일로 저장")
    void holdSavesNew() {
        when(repository.findByOrderNo("ord-1")).thenReturn(Optional.empty());
        Instant approvedAt = Instant.parse("2026-07-01T00:00:00Z");

        service.hold("ord-1", 20_000, approvedAt);

        ArgumentCaptor<EscrowHold> captor = ArgumentCaptor.forClass(EscrowHold.class);
        verify(repository).save(captor.capture());
        EscrowHold saved = captor.getValue();
        assertThat(saved.getOrderNo()).isEqualTo("ord-1");
        assertThat(saved.getAmount()).isEqualTo(20_000);
        assertThat(saved.getStatus()).isEqualTo(EscrowStatus.HELD);
        assertThat(saved.getHeldAt()).isEqualTo(approvedAt);
        assertThat(saved.getAutoReleaseAt()).isEqualTo(approvedAt.plus(7, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("hold 멱등: 같은 주문 홀드가 이미 있으면 저장 skip")
    void holdIsIdempotent() {
        when(repository.findByOrderNo("ord-1")).thenReturn(Optional.of(heldHold("ord-1", 20_000)));

        service.hold("ord-1", 20_000, Instant.now());

        verify(repository, never()).save(any());
    }

    // ---- release ----

    @Test
    @DisplayName("release: HELD → RELEASED 전이 + EscrowReleasedEvent 발행")
    void releaseTransitionsAndPublishes() {
        EscrowHold hold = heldHold("ord-1", 20_000);
        when(repository.findByOrderNo("ord-1")).thenReturn(Optional.of(hold));

        service.release("ord-1");

        assertThat(hold.getStatus()).isEqualTo(EscrowStatus.RELEASED);
        // 릴리스 상태 전이가 명시 saveAndFlush로 영속된다(OSIV off에서 dirty-checking 자동 flush에 의존하지 않음).
        verify(repository).saveAndFlush(hold);
        ArgumentCaptor<EscrowReleasedEvent> captor = ArgumentCaptor.forClass(EscrowReleasedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().orderNo()).isEqualTo("ord-1");
        assertThat(captor.getValue().amount()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("release 멱등: 이미 RELEASED면 이벤트 재발행 안 함")
    void releaseIsIdempotent() {
        EscrowHold hold = heldHold("ord-1", 20_000);
        hold.release(Instant.now()); // 이미 RELEASED
        when(repository.findByOrderNo("ord-1")).thenReturn(Optional.of(hold));

        service.release("ord-1");

        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("release: 홀드 없으면 ESCROW_NOT_FOUND")
    void releaseNotFound() {
        when(repository.findByOrderNo("ord-x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.release("ord-x"))
                .isInstanceOf(EscrowException.class)
                .satisfies(e -> assertThat(((EscrowException) e).code()).isEqualTo("ESCROW_NOT_FOUND"));

        verify(events, never()).publishEvent(any());
    }

    // ---- refundIfHeld ----

    @Test
    @DisplayName("refundIfHeld: HELD면 REFUNDED로 전이")
    void refundIfHeldRefunds() {
        EscrowHold hold = heldHold("ord-1", 20_000);
        when(repository.findByOrderNo("ord-1")).thenReturn(Optional.of(hold));

        service.refundIfHeld("ord-1");

        assertThat(hold.getStatus()).isEqualTo(EscrowStatus.REFUNDED);
        // 환불 상태 전이가 명시 saveAndFlush로 영속된다(OSIV off에서 dirty-checking 자동 flush에 의존하지 않음).
        verify(repository).saveAndFlush(hold);
    }

    @Test
    @DisplayName("refundIfHeld 멱등: 이미 RELEASED면 환불하지 않음(구매확정 후엔 회수 안 함)")
    void refundIfHeldSkipsReleased() {
        EscrowHold hold = heldHold("ord-1", 20_000);
        hold.release(Instant.now());
        when(repository.findByOrderNo("ord-1")).thenReturn(Optional.of(hold));

        service.refundIfHeld("ord-1");

        assertThat(hold.getStatus()).isEqualTo(EscrowStatus.RELEASED); // 그대로
    }

    @Test
    @DisplayName("refundIfHeld: 홀드 없으면 조용히 skip (비-에스크로 결제)")
    void refundIfHeldSkipsMissing() {
        when(repository.findByOrderNo("ord-x")).thenReturn(Optional.empty());

        service.refundIfHeld("ord-x"); // 예외 없이 skip
    }

    // ---- autoReleaseDue ----

    @Test
    @DisplayName("autoReleaseDue: 도래분을 모두 release하고 건수 반환")
    void autoReleaseReleasesDue() {
        EscrowHold h1 = heldHold("ord-1", 10_000);
        EscrowHold h2 = heldHold("ord-2", 20_000);
        when(repository.findByStatusAndAutoReleaseAtBefore(eq(EscrowStatus.HELD), any(), any(Pageable.class)))
                .thenReturn(List.of(h1, h2));
        when(repository.findByOrderNo("ord-1")).thenReturn(Optional.of(h1));
        when(repository.findByOrderNo("ord-2")).thenReturn(Optional.of(h2));

        int released = service.autoReleaseDue();

        assertThat(released).isEqualTo(2);
        assertThat(h1.getStatus()).isEqualTo(EscrowStatus.RELEASED);
        assertThat(h2.getStatus()).isEqualTo(EscrowStatus.RELEASED);
        verify(events, times(2)).publishEvent(any(EscrowReleasedEvent.class));
    }

    @Test
    @DisplayName("autoReleaseDue: 한 건 실패가 배치를 멈추지 않는다(격리) — 나머지는 처리")
    void autoReleaseIsolatesFailures() {
        EscrowHold h1 = heldHold("ord-1", 10_000);
        EscrowHold h2 = heldHold("ord-2", 20_000);
        when(repository.findByStatusAndAutoReleaseAtBefore(eq(EscrowStatus.HELD), any(), any(Pageable.class)))
                .thenReturn(List.of(h1, h2));
        // ord-1 재조회에서 예외를 유발 → release가 실패
        when(repository.findByOrderNo("ord-1")).thenThrow(new RuntimeException("DB 오류"));
        when(repository.findByOrderNo("ord-2")).thenReturn(Optional.of(h2));

        int released = service.autoReleaseDue();

        assertThat(released).isEqualTo(1); // ord-2만 성공
        assertThat(h2.getStatus()).isEqualTo(EscrowStatus.RELEASED);
    }

    // ---- getHold ----

    @Test
    @DisplayName("getHold: 홀드가 있으면 뷰로 매핑, 없으면 empty")
    void getHoldMapsView() {
        EscrowHold hold = heldHold("ord-1", 20_000);
        when(repository.findByOrderNo("ord-1")).thenReturn(Optional.of(hold));
        when(repository.findByOrderNo("ord-x")).thenReturn(Optional.empty());

        Optional<EscrowHoldView> view = service.getHold("ord-1");
        assertThat(view).isPresent();
        assertThat(view.get().orderNo()).isEqualTo("ord-1");
        assertThat(view.get().status()).isEqualTo(EscrowStatus.HELD);

        assertThat(service.getHold("ord-x")).isEmpty();
    }
}
