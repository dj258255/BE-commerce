package com.beomsu.becommerce.shorts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R23: 변환 파이프라인의 트랜잭션 경계를 실제 JPA 영속성 컨텍스트·실제 커밋으로 확인한다.
 *
 * <p>MySQL Flyway 마이그레이션 대신 H2 {@code create-drop}으로 {@link ShortVideo}
 * 스키마만 세운다({@code SettlementItemOptimisticLockTest}와 같은 관례, 인메모리·Docker
 * 불필요) — 보는 것은 스키마가 아니라 트랜잭션 경계와 동시 claim이다.
 *
 * <p>{@code @Transactional(propagation = NOT_SUPPORTED)}로 테스트 메서드 자체는 트랜잭션을
 * 열지 않는다 — 그래야 {@link ShortVideoTransitionService}의 각 메서드가 만드는 짧은
 * 트랜잭션이 실제로 독립적으로 커밋되고, 그 커밋을 테스트가 (같은 트랜잭션 안이 아니라) 별도
 * 조회로 확인할 수 있다.
 */
@DataJpaTest(showSql = false, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ContextConfiguration(classes = ShortVideoTransitionBoundaryTest.TestApp.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ShortVideoTransitionBoundaryTest {

    private static final UploadMeta VALID_META = new UploadMeta(30, 10_000_000L, 1080, 1920, "video/mp4");
    private static final TranscodeOutput COMPLETE_OUTPUT = new TranscodeOutput(
            "1080/out.m3u8", "720/out.m3u8", "480/out.m3u8", "master.m3u8", "thumb.jpg");

    @Autowired
    ShortVideoRepository repository;

    @Autowired
    ShortVideoTransitionService transitions;

    @Autowired
    ShortsTranscodeListener listener;

    @Autowired
    DelegatingTranscodeRunner runnerDelegate;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetDelegate() {
        runnerDelegate.delegate = new FakeTranscodeRunner();
    }

    private long createUploadedVideo(String objectKey) {
        ShortVideo video = repository.save(ShortVideo.upload(1L, objectKey, VALID_META));
        video.markUploaded();
        video = repository.save(video);
        return video.getId();
    }

    @Test
    @DisplayName("R23: probe·transcode를 부르는 동안에는 활성 트랜잭션이 없다")
    void noActiveTransactionDuringFfmpegCalls() {
        long id = createUploadedVideo("shorts/1/tx-check");
        List<Boolean> txActiveDuringCall = new ArrayList<>();
        TranscodeRunner runner = new TranscodeRunner() {
            @Override
            public ProbeResult probe(String objectKey) {
                txActiveDuringCall.add(TransactionSynchronizationManager.isActualTransactionActive());
                return ProbeResult.ok();
            }

            @Override
            public TranscodeResult transcode(String objectKey) {
                txActiveDuringCall.add(TransactionSynchronizationManager.isActualTransactionActive());
                return TranscodeResult.ok(COMPLETE_OUTPUT);
            }
        };
        ShortsTranscodeService service = new ShortsTranscodeService(transitions, runner);

        service.processUploaded(id);

        assertThat(txActiveDuringCall).hasSize(2).as("probe·transcode 호출 중 활성 트랜잭션이 있으면 안 된다")
                .allMatch(active -> !active);
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(ShortVideoStatus.READY);
    }

    @Test
    @DisplayName("R23: PROBING·TRANSCODING 전이는 각각 짧은 트랜잭션으로 커밋되어 FFmpeg 호출 시점에 이미 밖에서 보인다")
    void intermediateStatesAreCommittedBeforeFfmpegRuns() {
        long id = createUploadedVideo("shorts/1/intermediate-visible");
        List<ShortVideoStatus> observedAtProbe = new ArrayList<>();
        List<ShortVideoStatus> observedAtTranscode = new ArrayList<>();
        TranscodeRunner runner = new TranscodeRunner() {
            @Override
            public ProbeResult probe(String objectKey) {
                observedAtProbe.add(repository.findById(id).orElseThrow().getStatus());
                return ProbeResult.ok();
            }

            @Override
            public TranscodeResult transcode(String objectKey) {
                observedAtTranscode.add(repository.findById(id).orElseThrow().getStatus());
                return TranscodeResult.ok(COMPLETE_OUTPUT);
            }
        };
        ShortsTranscodeService service = new ShortsTranscodeService(transitions, runner);

        service.processUploaded(id);

        assertThat(observedAtProbe).containsExactly(ShortVideoStatus.PROBING);
        assertThat(observedAtTranscode).containsExactly(ShortVideoStatus.TRANSCODING);
    }

    @Test
    @DisplayName("R23: 두 워커가 동시에 UPLOADED→PROBING을 집어도 하나만 성공한다(조건부 UPDATE)")
    void concurrentClaimOnlySucceedsOnce() throws Exception {
        long id = createUploadedVideo("shorts/1/concurrent-claim");
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Boolean> claimAttempt = () -> {
            barrier.await();
            return transitions.claimForProbing(id);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = pool.invokeAll(List.of(claimAttempt, claimAttempt));
            long successCount = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    successCount++;
                }
            }
            assertThat(successCount).as("동시에 집어도 하나만 PROBING 전이에 성공해야 한다").isEqualTo(1);
        } finally {
            pool.shutdown();
        }
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(ShortVideoStatus.PROBING);
    }

    @Test
    @DisplayName("R23: 리스너가 (@ApplicationModuleListener가 합성하는) REQUIRES_NEW 트랜잭션에 들어와도 "
            + "NOT_SUPPORTED로 꺼서 하위 전이가 독립적으로 커밋된다")
    void listenerSuspendsOuterTransactionSoNestedTransitionsCommitIndependently() {
        long id = createUploadedVideo("shorts/1/listener-boundary");
        List<ShortVideoStatus> observedAtProbe = new ArrayList<>();
        List<ShortVideoStatus> observedAtTranscode = new ArrayList<>();
        runnerDelegate.delegate = new TranscodeRunner() {
            @Override
            public ProbeResult probe(String objectKey) {
                observedAtProbe.add(repository.findById(id).orElseThrow().getStatus());
                return ProbeResult.ok();
            }

            @Override
            public TranscodeResult transcode(String objectKey) {
                observedAtTranscode.add(repository.findById(id).orElseThrow().getStatus());
                return TranscodeResult.ok(COMPLETE_OUTPUT);
            }
        };

        // 실제 운영에서 @ApplicationModuleListener가 합성하는 @Transactional(REQUIRES_NEW)와
        // 똑같은 조건 — 리스너 메서드를 "이미 트랜잭션이 열린" 상태에서 부른다. onUploaded 쪽의
        // @Transactional(NOT_SUPPORTED)가 없으면 이 바깥 트랜잭션에 하위 전이가 전부 합류해
        // probe·transcode가 끝날 때까지 아무것도 커밋되지 않는다(실제로 겪은 증상).
        new TransactionTemplate(transactionManager).execute(status -> {
            listener.onUploaded(new ShortUploadedEvent(id));
            return null;
        });

        assertThat(observedAtProbe).containsExactly(ShortVideoStatus.PROBING);
        assertThat(observedAtTranscode).containsExactly(ShortVideoStatus.TRANSCODING);
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(ShortVideoStatus.READY);
    }

    /** 테스트마다 다른 {@link TranscodeRunner} 동작을 쓰기 위한 빈 — 실제 구현은 가변 필드로 바꿔 끼운다. */
    static class DelegatingTranscodeRunner implements TranscodeRunner {
        volatile TranscodeRunner delegate = new FakeTranscodeRunner();

        @Override
        public ProbeResult probe(String objectKey) {
            return delegate.probe(objectKey);
        }

        @Override
        public TranscodeResult transcode(String objectKey) {
            return delegate.transcode(objectKey);
        }
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = ShortVideo.class)
    static class TestApp {
        @Bean
        ShortVideoTransitionService shortVideoTransitionService(ShortVideoRepository repository) {
            return new ShortVideoTransitionService(repository);
        }

        @Bean
        DelegatingTranscodeRunner delegatingTranscodeRunner() {
            return new DelegatingTranscodeRunner();
        }

        @Bean
        ShortsTranscodeService shortsTranscodeService(
                ShortVideoTransitionService transitions, DelegatingTranscodeRunner runner) {
            return new ShortsTranscodeService(transitions, runner);
        }

        @Bean
        ShortsTranscodeListener shortsTranscodeListener(ShortsTranscodeService service) {
            return new ShortsTranscodeListener(service);
        }
    }
}
