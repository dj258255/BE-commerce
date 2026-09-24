package com.beomsu.becommerce.personalization.internal;

import com.beomsu.becommerce.testsupport.SharedContainers;
import com.beomsu.becommerce.personalization.UserActivityEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 컨텍스트 갱신의 <b>원자성</b>을 실 Redis로 검증한다.
 *
 * <p>여기서만 검증할 수 있는 이유: 병합이 <b>Lua 안</b>에 있기 때문이다. 모킹으로는 스크립트를
 * 실행할 수 없고, 자바 쪽만 보면 "원자적인가"를 물을 수 없다. 그래서 Redis 컨테이너 하나만 띄운다
 * (MySQL·Spring 컨텍스트가 필요 없다 — 가볍고 빠르다).
 *
 * <p><b>핵심 단언은 "동시 적용 후 seq가 최대값 그대로인가"다.</b> 예전 구현은 읽기 → 비교 → 쓰기를
 * 자바에서 했으므로, 높은 seq가 먼저 쓰인 뒤 낮은 seq를 읽은 스레드가 <b>그것을 덮어썼다</b>
 * (E2가 항목 유실로 관측). Lua로 옮기면 높은 seq가 쓰인 뒤에는 낮은 seq가 거절되므로 덮이지 않는다.
 */
@Tag("integration")
class ContextStoreConcurrencyTest {

    private static final long USER = 1L;
    private static final int MAX_ITEMS = 200;

    static final GenericContainer<?> REDIS = SharedContainers.redis();   // 공용 Redis(#276)

    static {
        SharedContainers.flushRedis();   // 예전에는 클래스마다 새 Redis 였다 — 빈 상태로 시작한다
    }

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private ContextStore store;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        store = new ContextStore(redis, mapper, Duration.ofDays(7), MAX_ITEMS,
                new PersonalizationMetrics(new SimpleMeterRegistry()));
        clearContext();
    }

    @AfterEach
    void tearDown() {
        factory.destroy();
    }

    /** 키 형식에 의존하지 않는다 — 값 판이 붙어도(:v2) 테스트가 깨지지 않게. */
    private void clearContext() {
        var keys = redis.keys("ctx:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /** itemId에 seq를 실어 보낸다 — 어떤 항목이 살아남았는지 셀 수 있게. */
    private UserActivityEvent event(long seq) {
        return new UserActivityEvent(USER, seq, "CLICK", seq, Instant.now(), "SYNTHETIC");
    }

    private UserActivityEvent typed(long seq, String type) {
        return new UserActivityEvent(USER, seq, type, seq, Instant.now(), "SYNTHETIC");
    }

    /** 목록 크기를 바꾼 저장소 — 잘림과 집계의 관계를 보려면 작은 max-items 가 필요하다. */
    private ContextStore storeWithMaxItems(int maxItems) {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new ContextStore(redis, mapper, Duration.ofDays(7), maxItems,
                new PersonalizationMetrics(new SimpleMeterRegistry()));
    }

    @Test
    @DisplayName("창 집계는 목록 잘림을 넘어선다 — 목록은 20건, 집계는 30건")
    void countsOutliveTheTruncatedList() {
        ContextStore small = storeWithMaxItems(20);
        for (long seq = 1; seq <= 30; seq++) {
            small.apply(event(seq));
        }

        OnlineContext context = small.read(USER).orElseThrow();
        // 목록은 잘렸다 — 이건 정상이다(컨텍스트는 "최근 것"을 담는다).
        assertThat(context.items()).hasSize(20);
        // 그런데 집계는 잘리지 않는다. 이 둘이 갈라지는 것이 E2 가 집계 60% 를 본 이유다.
        assertThat(context.totalActivities()).isEqualTo(30);
        assertThat(context.counts()).containsEntry("CLICK", 30L);
    }

    @Test
    @DisplayName("집계는 유형별로 센다")
    void countsArePerActivityType() {
        store.apply(typed(1, "VIEW"));
        store.apply(typed(2, "VIEW"));
        store.apply(typed(3, "CLICK"));

        assertThat(store.read(USER).orElseThrow().counts())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("VIEW", 2L, "CLICK", 1L));
    }

    @Test
    @DisplayName("재배달은 집계를 두 번 세지 않는다 — 멱등이 목록과 집계에 함께 걸린다")
    void redeliveryIsNotCountedTwice() {
        store.apply(event(1));
        store.apply(event(1));
        store.apply(event(1));

        OnlineContext context = store.read(USER).orElseThrow();
        assertThat(context.items()).hasSize(1);
        assertThat(context.totalActivities()).isEqualTo(1);
    }

    @Test
    @DisplayName("도착 순서가 뒤집혀도 목록과 집계가 같다 — 순서에 관대한 병합의 짝")
    void countsDoNotDependOnArrivalOrder() {
        for (long seq = 1; seq <= 10; seq++) {
            store.apply(typed(seq, "VIEW"));
        }
        OnlineContext inOrder = store.read(USER).orElseThrow();

        clearContext();
        for (long seq = 10; seq >= 1; seq--) {
            store.apply(typed(seq, "VIEW"));
        }
        OnlineContext reversed = store.read(USER).orElseThrow();

        assertThat(reversed.counts()).isEqualTo(inOrder.counts());
        // occurredAt 은 두 실행에서 다르므로 순서를 seq 로 비교한다 — 값이 아니라 **배열**이 같아야 한다.
        assertThat(reversed.items()).extracting(OnlineContext.Item::seq)
                .isEqualTo(inOrder.items().stream().map(OnlineContext.Item::seq).toList());
    }

    @Test
    @DisplayName("같은 이벤트를 다시 적용해도 1건 — 재배달 멱등")
    void reapplyIsIdempotent() {
        store.apply(event(1));
        store.apply(event(1));
        store.apply(event(1));

        OnlineContext context = store.read(USER).orElseThrow();
        assertThat(context.seq()).isEqualTo(1L);
        assertThat(context.items()).hasSize(1);
    }

    @Test
    @DisplayName("낮은 seq도 제자리에 들어간다 — 버리지 않는다")
    void lowerSeqIsInsertedInPlace() {
        store.apply(event(5));
        store.apply(event(3));

        OnlineContext context = store.read(USER).orElseThrow();
        // 대표 순번은 되돌아가지 않는다(5가 최대다).
        assertThat(context.seq()).isEqualTo(5L);
        // 그러나 늦게 온 3도 남는다 — 버리지 않고 seq 내림차순 위치에 들어간다.
        assertThat(context.items()).extracting(OnlineContext.Item::seq).containsExactly(5L, 3L);
        assertThat(context.items()).extracting(OnlineContext.Item::itemId).containsExactly(5L, 3L);
    }

    @Test
    @DisplayName("도착 순서가 달라도 결과가 같다 — 병합이 순서에 무관하다")
    void arrivalOrderDoesNotChangeResult() {
        List<Long> seqs = List.of(4L, 1L, 7L, 2L, 9L, 5L, 8L, 3L, 6L, 10L);
        for (long seq : seqs) {
            store.apply(event(seq));
        }
        OnlineContext shuffled = store.read(USER).orElseThrow();

        // 같은 집합을 순서대로 적용한 결과와 같아야 한다 — 이게 이 설계의 핵심 성질이다.
        clearContext();
        for (long seq = 1; seq <= 10; seq++) {
            store.apply(event(seq));
        }
        OnlineContext ordered = store.read(USER).orElseThrow();

        assertThat(shuffled.seq()).isEqualTo(ordered.seq());
        assertThat(shuffled.items()).extracting(OnlineContext.Item::seq)
                .containsExactlyElementsOf(ordered.items().stream().map(OnlineContext.Item::seq).toList());
    }

    @Test
    @DisplayName("max-items를 넘으면 가장 낮은 seq부터 버린다 — 적용 순서가 아니라 나이 기준")
    void trimsByLowestSeq() {
        store.apply(event(9));
        store.apply(event(1));
        store.apply(event(8));

        ContextStore small = new ContextStore(redis, mapper(), Duration.ofDays(7), 2,
                new PersonalizationMetrics(new SimpleMeterRegistry()));
        clearContext();
        small.apply(event(9));
        small.apply(event(1));
        small.apply(event(8));

        OnlineContext context = small.read(USER).orElseThrow();
        assertThat(context.items()).extracting(OnlineContext.Item::seq).containsExactly(9L, 8L);
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    @DisplayName("max-items를 넘으면 오래된 것부터 버린다 — 컨텍스트가 무한히 자라지 않는다")
    void trimsToMaxItems() {
        for (long seq = 1; seq <= MAX_ITEMS + 10; seq++) {
            store.apply(event(seq));
        }

        OnlineContext context = store.read(USER).orElseThrow();
        assertThat(context.seq()).isEqualTo((long) MAX_ITEMS + 10);
        assertThat(context.items()).hasSize(MAX_ITEMS);
        // 가장 최근 것이 남고, 가장 오래된 것이 잘려나갔다.
        assertThat(context.items()).extracting(OnlineContext.Item::itemId)
                .contains((long) MAX_ITEMS + 10)
                .doesNotContain(1L);
    }

    @Test
    @DisplayName("동시에 적용해도 최대 seq가 덮이지 않는다 — 읽기·비교·쓰기가 한 번에 끝난다")
    void concurrentAppliesDoNotOverwriteHigherSeq() throws Exception {
        int count = 64;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (long seq = 1; seq <= count; seq++) {
            long value = seq;
            tasks.add(() -> {
                start.await();
                store.apply(event(value));
                return null;
            });
        }

        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(pool.submit(task));
        }
        start.countDown();
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        OnlineContext context = store.read(USER).orElseThrow();
        // 읽기·비교·쓰기가 나뉘어 있으면, 높은 seq가 먼저 쓰인 뒤 낮은 seq를 읽은 스레드가 덮어써
        // 여기가 count보다 작아진다. Lua 는 높은 seq가 쓰인 뒤 낮은 seq를 거절하므로 그럴 수 없다.
        assertThat(context.seq()).isEqualTo((long) count);

        // 항목에 중복이 없어야 한다 — seq 게이트가 같은 항목을 두 번 넣지 않는다.
        List<Long> ids = context.items().stream().map(OnlineContext.Item::itemId).toList();
        Set<Long> unique = new HashSet<>(ids);
        assertThat(unique).hasSameSizeAs(ids);
        // 버려진 seq가 있더라도(동시 적용 순서) 항목이 중복으로 남지는 않는다.
        assertThat(ids).allMatch(id -> id <= count);
    }

    @Test
    @DisplayName("동시 적용에서 사용자끼리 섞이지 않는다")
    void concurrentAppliesStayIsolatedPerUser() throws Exception {
        int users = 8;
        int perUser = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        for (long user = 1; user <= users; user++) {
            long userId = user;
            futures.add(pool.submit(() -> {
                start.await();
                for (long seq = 1; seq <= perUser; seq++) {
                    store.apply(new UserActivityEvent(userId, seq, "CLICK", seq, Instant.now(), "SYNTHETIC"));
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        for (long user = 1; user <= users; user++) {
            OnlineContext context = store.read(user).orElseThrow();
            assertThat(context.seq()).isEqualTo((long) perUser);
            assertThat(context.items()).extracting(OnlineContext.Item::itemId)
                    .containsExactlyInAnyOrderElementsOf(
                            java.util.stream.LongStream.rangeClosed(1, perUser).boxed().toList());
        }
    }
}
