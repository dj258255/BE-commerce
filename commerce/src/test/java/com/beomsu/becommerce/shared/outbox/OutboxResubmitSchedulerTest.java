package com.beomsu.becommerce.shared.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxResubmitSchedulerTest {

    private static EventPublication publication(Instant publicationDate, Instant completionDate) {
        EventPublication p = mock(EventPublication.class);
        when(p.getIdentifier()).thenReturn(UUID.randomUUID());
        when(p.getPublicationDate()).thenReturn(publicationDate);
        when(p.getCompletionDate()).thenReturn(Optional.ofNullable(completionDate));
        return p;
    }

    /** 스케줄러가 건넨 predicate 를 그대로 적용해 "다시 받은" 발행만 모으는 가짜. */
    private static final class RecordingIncomplete implements IncompleteEventPublications {
        private final List<EventPublication> publications = new ArrayList<>();
        private final List<EventPublication> resubmitted = new ArrayList<>();

        @Override
        public void resubmitIncompletePublications(Predicate<EventPublication> filter) {
            publications.stream().filter(filter).forEach(resubmitted::add);
        }

        @Override
        public void resubmitIncompletePublicationsOlderThan(Duration duration) {
            throw new UnsupportedOperationException("이 스케줄러는 Predicate 변형을 쓴다");
        }
    }

    private OutboxResubmitScheduler scheduler(IncompleteEventPublications incomplete, MeterRegistry registry) {
        return new OutboxResubmitScheduler(incomplete, registry, Duration.ofMinutes(5), 100);
    }

    private EventPublication aged() {
        return publication(Instant.now().minusSeconds(600), null);
    }

    @Test
    @DisplayName("(a) min-age 를 넘긴 미완료 발행은 재제출된다 — 어린 미완료는 건드리지 않는다")
    void resubmitsAgedIncompleteOnly() {
        RecordingIncomplete incomplete = new RecordingIncomplete();
        EventPublication aged = aged();
        EventPublication fresh = publication(Instant.now().minusSeconds(10), null);
        incomplete.publications.add(aged);
        incomplete.publications.add(fresh);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        scheduler(incomplete, registry).run();

        assertThat(incomplete.resubmitted).containsExactly(aged);   // 리스너가 다시 받는다
        assertThat(registry.counter("outbox.resubmitted").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("(b) 완료된 발행은 재제출되지 않는다(completion-mode=archive 상호작용)")
    void doesNotResubmitCompleted() {
        RecordingIncomplete incomplete = new RecordingIncomplete();
        incomplete.publications.add(publication(Instant.now().minusSeconds(600), Instant.now().minusSeconds(300)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        scheduler(incomplete, registry).run();

        assertThat(incomplete.resubmitted).isEmpty();
        assertThat(registry.find("outbox.resubmitted").counter()).isNull(); // 한 건도 안 나갔다
    }

    @Test
    @DisplayName("(d) max-per-tick 상한만큼만 재제출한다")
    void capsAtMaxPerTick() {
        RecordingIncomplete incomplete = new RecordingIncomplete();
        for (int i = 0; i < 5; i++) {
            incomplete.publications.add(aged());
        }
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new OutboxResubmitScheduler(incomplete, registry, Duration.ofMinutes(5), 2).run();

        assertThat(incomplete.resubmitted).hasSize(2);
        assertThat(registry.counter("outbox.resubmitted").count()).isEqualTo(2.0);
    }

    /** @Value String→Duration 변환은 부트가 등록하는 ConversionService 가 필요하다 — 러너에도 같은 것을 붙인다. */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(OutboxResubmitScheduler.class)
                .withBean(IncompleteEventPublications.class, () -> mock(IncompleteEventPublications.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new);
    }

    @Test
    @DisplayName("(c) enabled=false 면 스케줄러 빈이 등록되지 않는다 — true 면 등록된다")
    void gatesSchedulerBean() {
        runner().run(context -> assertThat(context).doesNotHaveBean(OutboxResubmitScheduler.class));

        runner().withPropertyValues("app.outbox.resubmit.enabled=true",
                        "app.outbox.resubmit.min-age=PT5M",
                        "app.outbox.resubmit.max-per-tick=100")
                .run(context -> assertThat(context).hasSingleBean(OutboxResubmitScheduler.class));
    }
}
