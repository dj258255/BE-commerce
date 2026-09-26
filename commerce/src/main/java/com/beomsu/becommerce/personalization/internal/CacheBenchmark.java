package com.beomsu.becommerce.personalization.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * E6 캐시 압축 벤치 — <b>같은 값, 같은 왕복</b>으로 크기 구간별 압축의 손익을 잰다.
 *
 * <p><b>왜 앱 안에서 재나</b>: 이 실험의 값은 <b>직렬화·압축·왕복이 함께 만든 지연</b>이다. 넷을
 * 각각 밖에서 재면 합쳐질 때의 상호작용(예: 압축이 CPU 를 쓰느라 왕복이 밀리는 것)을 놓친다.
 * 그래서 앱이 실제로 쓰는 경로({@link CacheValueCompressor} → Redis)를 그대로 지나간다.
 *
 * <p><b>구간을 나눠 재는 이유</b>: "압축이 느린가"와 "왕복이 느린가"는 처방이 다르다. 전자는
 * 코덱 선택의 문제고 후자는 값 크기의 문제다. 그래서 압축·SET·GET·해제를 따로 남긴다.
 *
 * <p><b>한계를 숨기지 않는다</b>: ① Redis 가 <b>로컬</b>이라 "전송량"은 루프백이다 — 실제
 * 네트워크면 압축의 이득이 <b>더 커진다</b>(바이트가 줄어드는 것이 지연으로 돌아온다). 그래서
 * 여기서 나온 임계값은 <b>상한</b>이다. ② 앱 CPU 는 <b>이 스레드의 CPU 시간</b>이라 GC·다른
 * 스레드의 영향은 안 들어간다. ③ bench 키는 TTL 을 걸고 끝나면 지운다.
 */
@Component
public class CacheBenchmark {

    private static final Logger log = LoggerFactory.getLogger(CacheBenchmark.class);
    private static final String PREFIX = "bench:cache:";
    /** 벤치 키 TTL — 실험 중 죽어도 Redis 를 영원히 채우지 않는다. */
    private static final long TTL_SECONDS = 300;

    private final StringRedisTemplate redis;
    private final CacheValueCompressor compressor;

    public CacheBenchmark(StringRedisTemplate redis, CacheValueCompressor compressor) {
        this.redis = redis;
        this.compressor = compressor;
    }

    /** 한 구간의 결과 — 리포트 표가 이걸 그대로 옮긴다. */
    public record Result(String codec, int thresholdBytes, long rawBytes, int count,
                         long rawBytesTotal, long storedBytesTotal,
                         double setP50, double setP95, double setP99,
                         double getP50, double getP95, double getP99,
                         double compressP50, double decompressP50,
                         double appCpuMs, Long redisUsedMemoryBytes, Long redisMemoryDeltaBytes,
                         long roundTrips,
                         int threads, double totalP95, double totalP99, double cpuPerOpUs) {

        /** 저장량 / 원본량. 1 미만이면 실제로 줄었다는 뜻이다(base64 팽창 포함). */
        public double ratio() {
            return rawBytesTotal == 0 ? 0 : (double) storedBytesTotal / rawBytesTotal;
        }
    }

    /**
     * 주어진 크기의 값을 {@code count}개 넣고 <b>같은 순서로</b> 읽는다.
     *
     * <p>값은 JSON 이다 — 이 저장소의 캐시가 JSON 문자열이기 때문이다(ContextStore). 크기는
     * 채움 필드로 맞추고, <b>실제 바이트를 함께 보고한다</b>(요청한 크기와 다를 수 있다).
     */
    public Result run(int requestedBytes, int count) {
        return run(requestedBytes, count, 1);
    }

    /**
     * {@code threads} 개 스레드가 동시에 각자 {@code count} 개를 넣고 읽는다(#266). 한 스레드로 잰 E6 은 압축끼리 CPU 를 다투는
     * 상황을 보지 못했다. 연결은 앱과 같은 {@code StringRedisTemplate}(공유 연결)을 쓴다.
     *
     * <p>CPU 는 스레드마다 잰 CPU 시간의 합이다. 지연은 모든 스레드의 연산을 모아 백분위를 낸다.
     */
    public Result run(int requestedBytes, int count, int threads) {
        int n = Math.max(threads, 1);
        if (n == 1) {
            return runSingle(requestedBytes, count, 1);
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Result>> futures = new ArrayList<>();
        Long memoryBefore = usedMemoryBytes();
        try {
            for (int t = 0; t < n; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return runSingle(requestedBytes, count, n);
                }));
            }
            start.countDown();
            List<Result> parts = new ArrayList<>();
            for (java.util.concurrent.Future<Result> f : futures) {
                parts.add(f.get());
            }
            return merge(parts, n, memoryBefore);
        } catch (Exception e) {
            throw new IllegalStateException("동시 벤치 실패", e);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 스레드별 결과를 합친다. 백분위는 스레드별 원자료를 다시 모아 낸다(스레드 p95 의 평균이 아니다). */
    private Result merge(List<Result> parts, int threads, Long memoryBefore) {
        List<long[]> sets = new ArrayList<>();
        List<long[]> gets = new ArrayList<>();
        List<long[]> totals = new ArrayList<>();
        List<long[]> comps = new ArrayList<>();
        List<long[]> decomps = new ArrayList<>();
        long rawTotal = 0;
        long storedTotal = 0;
        double cpuMs = 0;
        int ops = 0;
        for (Result r : parts) {
            Raw raw = lastRaw.remove(r);
            sets.add(raw.set);
            gets.add(raw.get);
            totals.add(raw.total);
            comps.add(raw.compress);
            decomps.add(raw.decompress);
            rawTotal += r.rawBytesTotal();
            storedTotal += r.storedBytesTotal();
            cpuMs += r.appCpuMs();
            ops += r.count();
        }
        Result first = parts.get(0);
        long[] set = concat(sets);
        long[] get = concat(gets);
        long[] total = concat(totals);
        return new Result(first.codec(), first.thresholdBytes(), first.rawBytes(), ops, rawTotal, storedTotal,
                pct(set, 50), pct(set, 95), pct(set, 99), pct(get, 50), pct(get, 95), pct(get, 99),
                pct(concat(comps), 50), pct(concat(decomps), 50), cpuMs, usedMemoryBytes(), null, ops,
                threads, pct(total, 95), pct(total, 99), cpuMs * 1000.0 / ops);
    }

    private static long[] concat(List<long[]> arrays) {
        long[] out = new long[arrays.stream().mapToInt(a -> a.length).sum()];
        int k = 0;
        for (long[] a : arrays) {
            System.arraycopy(a, 0, out, k, a.length);
            k += a.length;
        }
        return out;
    }

    /** 스레드별 원자료. 합칠 때 백분위를 다시 내려고 결과 객체와 짝지어 둔다. */
    private record Raw(long[] set, long[] get, long[] total, long[] compress, long[] decompress) {
    }

    private final java.util.Map<Result, Raw> lastRaw = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    private Result runSingle(int requestedBytes, int count, int threads) {
        int size = Math.max(requestedBytes, 64);
        int total = Math.max(count, 1);
        String runId = UUID.randomUUID().toString().substring(0, 8);
        String json = payload(size);

        long[] set = new long[total];
        long[] get = new long[total];
        long[] compress = new long[total];
        long[] decompress = new long[total];
        long[] whole = new long[total];
        long storedTotal = 0;
        List<String> keys = new ArrayList<>(total);

        Long memoryBefore = usedMemoryBytes();
        long cpuBefore = threadCpuNanos();

        for (int i = 0; i < total; i++) {
            String key = PREFIX + runId + ":" + i;
            keys.add(key);

            long c0 = System.nanoTime();
            String stored = compressor.store(json);
            long c1 = System.nanoTime();

            redis.opsForValue().set(key, stored);
            long c2 = System.nanoTime();

            String read = redis.opsForValue().get(key);
            long c3 = System.nanoTime();

            compressor.load(read);
            long c4 = System.nanoTime();

            compress[i] = c1 - c0;
            set[i] = c2 - c1;
            get[i] = c3 - c2;
            decompress[i] = c4 - c3;
            whole[i] = c4 - c0;
            storedTotal += CacheValueCompressor.storedBytes(stored);
        }

        long cpuAfter = threadCpuNanos();
        Long memoryAfter = usedMemoryBytes();

        // 정리 — 측정 구간 밖이다. 남겨 두면 다음 구간의 메모리 측정이 앞 구간의 값을 본다.
        redis.delete(keys);

        long rawBytes = json.getBytes(StandardCharsets.UTF_8).length;
        Result result = new Result(compressor.codec().name(), compressor.thresholdBytes(),
                rawBytes, total, rawBytes * total, storedTotal,
                pct(set, 50), pct(set, 95), pct(set, 99),
                pct(get, 50), pct(get, 95), pct(get, 99),
                pct(compress, 50), pct(decompress, 50),
                (cpuAfter - cpuBefore) / 1_000_000.0,
                memoryAfter,
                (memoryAfter == null || memoryBefore == null) ? null : memoryAfter - memoryBefore,
                total, threads, pct(whole, 95), pct(whole, 99), (cpuAfter - cpuBefore) / 1000.0 / total);
        if (threads > 1) {
            lastRaw.put(result, new Raw(set, get, whole, compress, decompress));    // merge 가 꺼내 간다
        }

        log.info("E6 벤치 — 코덱={} 요청크기={}B 실제={}B 저장/원본={} 배 set p50={}ms get p50={}ms",
                result.codec(), requestedBytes, result.rawBytes(), String.format("%.3f", result.ratio()),
                String.format("%.3f", result.setP50()), String.format("%.3f", result.getP50()));
        return result;
    }

    /**
     * 요청한 크기에 맞춘 JSON — <b>실제 캐시 값의 모양</b>을 흉내 낸다.
     *
     * <p>이게 중요한 이유: 압축률은 값의 <b>성질</b>에 달렸다. 무작위 바이트를 넣으면 압축은
     * 언제나 손해이고, 완전히 같은 문자를 반복하면 언제나 이득이다 — 둘 다 현실이 아니다.
     * 컨텍스트 캐시({@code ctx:{userId}})는 <b>반복되는 키 이름</b>과 <b>짧은 값</b>으로 이루어진
     * JSON 이고, 그래서 "구조는 반복되고 내용은 조금씩 다르다"가 이 실험이 재야 할 모양이다.
     *
     * <p>그래도 <b>이 모양에서의 결과</b>라는 한계는 리포트에 적는다 — 값이 사진·벡터처럼
     * 고엔트로피면 교차점이 훨씬 오른쪽으로 간다.
     */
    static String payload(int size) {
        StringBuilder sb = new StringBuilder(size + 256);
        sb.append("{\"seq\":1234567890123,\"updatedAt\":\"2026-09-21T00:00:00Z\",\"items\":[");
        int i = 0;
        while (sb.length() < size) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"seq\":").append(1_000_000 + i)
                    .append(",\"itemId\":").append(1_000_000 + (i % 97))
                    .append(",\"activityType\":\"").append(i % 2 == 0 ? "VIEW" : "CLICK")
                    .append("\",\"occurredAt\":\"2026-09-21T00:00:00Z\"}");
            i++;
        }
        sb.append("],\"counts\":{\"VIEW\":").append(i / 2).append(",\"CLICK\":").append(i - i / 2).append("}}");
        return sb.toString();
    }

    private long threadCpuNanos() {
        return ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
    }

    /** Redis used_memory — 못 읽으면 null(지표가 없다고 값을 지어내지 않는다). */
    private Long usedMemoryBytes() {
        try {
            return redis.execute((RedisCallback<Long>) connection -> {
                Properties info = info(connection);
                if (info == null) {
                    return null;
                }
                Object value = info.get("used_memory");
                return value == null ? null : Long.parseLong(String.valueOf(value));
            });
        } catch (RuntimeException e) {
            log.debug("Redis 메모리를 읽지 못했다 — null 로 둔다: {}", e.toString());
            return null;
        }
    }

    private static Properties info(RedisConnection connection) {
        try {
            return connection.serverCommands().info("memory");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 나노초 배열의 백분위(ms). */
    private static double pct(long[] nanos, int percentile) {
        long[] sorted = Arrays.copyOf(nanos, nanos.length);
        Arrays.sort(sorted);
        int index = Math.min(sorted.length - 1, Math.max(0, (int) Math.ceil(percentile / 100.0 * sorted.length) - 1));
        return sorted[index] / 1_000_000.0;
    }
}
