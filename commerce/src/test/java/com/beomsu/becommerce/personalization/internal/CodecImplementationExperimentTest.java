package com.beomsu.becommerce.personalization.internal;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.xerial.snappy.Snappy;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SNAPPY 가 LZ4 보다 싼 이유를 구현별로 가른다(#346, ADR-041).
 *
 * <p>코드는 {@code LZ4Factory.fastestInstance()} 를 쓴다. JNI 가 있으면 JNI, 없으면 Unsafe Java 를 고른다. 이 플랫폼에서 무엇이
 * 골라졌는지 적고, 같은 페이로드({@link CacheBenchmark#payload})에서 구현마다 압축 + 해제 한 왕복의 스레드 CPU 시간을 잰다.
 * 우리 코덱 층({@link CacheValueCodec#LZ4})을 따로 재 라이브러리 밖의 비용(길이 머리 · 배열 복사)을 가른다.
 *
 * <p>JMH 가 아니다. 구현마다 데운 뒤 라운드 여러 번의 중앙값을 쓰고, 순서 편향을 줄이려고 라운드마다 구현 순서를 돌린다.
 * 결과는 환경 변수 {@code CODEC_OUT=경로} 로 JSON 을 남긴다(Gradle 의 -D 는 테스트 JVM 에 닿지 않는다).
 *
 * <pre>CODEC_OUT=/tmp/codec.json ./gradlew -p commerce experimentTest --tests '*CodecImplementationExperimentTest'</pre>
 */
@Tag("experiment")
class CodecImplementationExperimentTest {

    private static final int[] SIZES = {1_024, 51_200, 512_000};
    private static final long WARMUP_NANOS = 3_000_000_000L;
    private static final int ROUNDS = 15;
    private static final long ROUND_NANOS = 400_000_000L;

    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();

    /** 한 왕복(압축 → 해제)을 하고 복원 길이를 돌려준다. 결과를 쓰게 해 JIT 가 지우지 못하게 한다. */
    interface RoundTrip {
        int run(byte[] raw) throws IOException;
    }

    @Test
    @DisplayName("구현별 압축 + 해제 CPU 시간과 이 플랫폼에서 골라진 구현을 남긴다")
    void measure() throws Exception {
        Map<String, Object> platform = new LinkedHashMap<>();
        platform.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        platform.put("java", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        platform.put("lz4Fastest", LZ4Factory.fastestInstance().toString());
        platform.put("lz4FastestJava", LZ4Factory.fastestJavaInstance().toString());
        String jni;
        try {
            jni = LZ4Factory.nativeInstance().toString();
        } catch (Throwable t) {
            jni = "없음: " + t.getClass().getSimpleName() + " " + t.getMessage();
        }
        platform.put("lz4Jni", jni);
        platform.put("snappyNative", Snappy.getNativeLibraryVersion());

        Map<String, RoundTrip> impls = new LinkedHashMap<>();
        impls.put("SNAPPY", raw -> Snappy.uncompress(Snappy.compress(raw)).length);
        impls.put("LZ4_FASTEST", lz4(LZ4Factory.fastestInstance()));
        if (!jni.startsWith("없음")) {
            impls.put("LZ4_JNI", lz4(LZ4Factory.nativeInstance()));
        }
        impls.put("LZ4_UNSAFE", lz4(LZ4Factory.unsafeInstance()));
        impls.put("LZ4_SAFE", lz4(LZ4Factory.safeInstance()));
        impls.put("CODEC_LZ4", raw -> CacheValueCodec.LZ4.decompress(CacheValueCodec.LZ4.compress(raw)).length);
        impls.put("CODEC_SNAPPY", raw -> CacheValueCodec.SNAPPY.decompress(CacheValueCodec.SNAPPY.compress(raw)).length);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int size : SIZES) {
            byte[] raw = CacheBenchmark.payload(size).getBytes(StandardCharsets.UTF_8);
            for (RoundTrip rt : impls.values()) {
                assertThat(rt.run(raw)).isEqualTo(raw.length);          // 복원이 맞아야 잴 가치가 있다
                warm(rt, raw);
            }
            Map<String, double[]> perOp = new LinkedHashMap<>();
            impls.keySet().forEach(k -> perOp.put(k, new double[ROUNDS]));
            List<String> order = new ArrayList<>(impls.keySet());
            for (int round = 0; round < ROUNDS; round++) {
                for (int i = 0; i < order.size(); i++) {
                    String name = order.get((i + round) % order.size());    // 라운드마다 순서를 돌린다
                    perOp.get(name)[round] = cpuNanosPerOp(impls.get(name), raw);
                }
            }
            for (Map.Entry<String, double[]> e : perOp.entrySet()) {
                double[] v = e.getValue().clone();
                Arrays.sort(v);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sizeBytes", raw.length);
                row.put("impl", e.getKey());
                row.put("cpuUsPerRoundTripMedian", round2(v[v.length / 2] / 1000.0));
                row.put("cpuUsPerRoundTripMin", round2(v[0] / 1000.0));
                row.put("cpuUsPerRoundTripMax", round2(v[v.length - 1] / 1000.0));
                row.put("storedBytes", storedBytes(e.getKey(), raw));
                rows.add(row);
                System.out.printf("[CODEC] %7d B  %-13s median %10.2f µs  (min %.2f · max %.2f)%n", raw.length, e.getKey(),
                        v[v.length / 2] / 1000.0, v[0] / 1000.0, v[v.length - 1] / 1000.0);
            }
        }
        System.out.println("[CODEC] platform " + platform);
        String out = System.getenv("CODEC_OUT");
        if (out != null) {
            Files.writeString(Path.of(out), toJson(Map.of("platform", platform, "rows", rows)));
        }
        assertThat(rows).isNotEmpty();
    }

    /**
     * 차가운 변형(#346 두 번째 댓글): 앱에서는 호출 사이에 Redis 입출력 · 할당이 끼어 캐시가 식는다. 호출마다 64MB 를 훑어
     * 캐시를 비운 뒤 한 번만 재고, 훑는 시간은 재지 않는다. 결과는 {@code CODEC_COLD_OUT} 로 남긴다.
     */
    @Test
    @DisplayName("캐시를 비운 뒤 한 번씩 재면 구현별 차이가 커지는가")
    void measureCold() throws Exception {
        byte[] sweep = new byte[64 * 1024 * 1024];
        Map<String, RoundTrip> impls = new LinkedHashMap<>();
        impls.put("SNAPPY", raw -> Snappy.uncompress(Snappy.compress(raw)).length);
        impls.put("LZ4_FASTEST", lz4(LZ4Factory.fastestInstance()));
        impls.put("LZ4_UNSAFE", lz4(LZ4Factory.unsafeInstance()));
        impls.put("CODEC_LZ4", raw -> CacheValueCodec.LZ4.decompress(CacheValueCodec.LZ4.compress(raw)).length);
        impls.put("CODEC_SNAPPY", raw -> CacheValueCodec.SNAPPY.decompress(CacheValueCodec.SNAPPY.compress(raw)).length);
        int samples = 300;
        List<Map<String, Object>> rows = new ArrayList<>();
        long sink = 0;
        for (int size : new int[] {51_200, 512_000}) {
            byte[] raw = CacheBenchmark.payload(size).getBytes(StandardCharsets.UTF_8);
            for (RoundTrip rt : impls.values()) {
                long end = System.nanoTime() + 1_000_000_000L;                 // JIT 는 데우고 캐시만 식힌다
                while (System.nanoTime() < end) {
                    sink += rt.run(raw);
                }
            }
            Map<String, long[]> cpu = new LinkedHashMap<>();
            impls.keySet().forEach(k -> cpu.put(k, new long[samples]));
            List<String> order = new ArrayList<>(impls.keySet());
            for (int i = 0; i < samples; i++) {
                for (int j = 0; j < order.size(); j++) {
                    String name = order.get((i + j) % order.size());
                    for (int k = 0; k < sweep.length; k += 64) {                // 캐시를 비운다(재지 않는다)
                        sweep[k]++;
                        sink += sweep[k];
                    }
                    long c0 = threads.getCurrentThreadCpuTime();
                    sink += impls.get(name).run(raw);
                    cpu.get(name)[i] = threads.getCurrentThreadCpuTime() - c0;
                }
            }
            for (Map.Entry<String, long[]> e : cpu.entrySet()) {
                long[] v = e.getValue().clone();
                Arrays.sort(v);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sizeBytes", raw.length);
                row.put("impl", e.getKey());
                row.put("cpuUsPerRoundTripMedian", round2(v[v.length / 2] / 1000.0));
                row.put("cpuUsPerRoundTripP90", round2(v[(int) (v.length * 0.9)] / 1000.0));
                rows.add(row);
                System.out.printf("[CODEC-COLD] %7d B  %-13s median %10.2f µs  p90 %.2f%n", raw.length, e.getKey(),
                        v[v.length / 2] / 1000.0, v[(int) (v.length * 0.9)] / 1000.0);
            }
        }
        assertThat(sink).isNotZero();
        String out = System.getenv("CODEC_COLD_OUT");
        if (out != null) {
            Files.writeString(Path.of(out), toJson(Map.of("rows", rows)));
        }
    }

    private static RoundTrip lz4(LZ4Factory factory) {
        LZ4Compressor c = factory.fastCompressor();
        LZ4FastDecompressor d = factory.fastDecompressor();
        return raw -> {
            byte[] out = new byte[c.maxCompressedLength(raw.length)];
            int size = c.compress(raw, 0, raw.length, out, 0, out.length);
            byte[] back = new byte[raw.length];
            d.decompress(out, 0, back, 0, raw.length);
            return back.length + (size > 0 ? 0 : 1);
        };
    }

    private void warm(RoundTrip rt, byte[] raw) throws IOException {
        long end = System.nanoTime() + WARMUP_NANOS;
        while (System.nanoTime() < end) {
            rt.run(raw);
        }
    }

    /** 한 라운드(약 {@link #ROUND_NANOS}) 동안 돌린 왕복의 스레드 CPU 시간 ÷ 횟수. */
    private double cpuNanosPerOp(RoundTrip rt, byte[] raw) throws IOException {
        long sink = 0;
        long ops = 0;
        long wallEnd = System.nanoTime() + ROUND_NANOS;
        long cpu0 = threads.getCurrentThreadCpuTime();
        while (System.nanoTime() < wallEnd) {
            sink += rt.run(raw);
            ops++;
        }
        long cpu = threads.getCurrentThreadCpuTime() - cpu0;
        assertThat(sink).isPositive();
        return cpu / (double) ops;
    }

    private static Object storedBytes(String impl, byte[] raw) throws IOException {
        return switch (impl) {
            case "SNAPPY", "CODEC_SNAPPY" -> CacheValueCodec.SNAPPY.compress(raw).length;
            case "LZ4_SAFE" -> LZ4Factory.safeInstance().fastCompressor().compress(raw).length;
            default -> CacheValueCodec.LZ4.compress(raw).length;
        };
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    @SuppressWarnings("unchecked")
    private static String toJson(Object o) {
        if (o instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            m.forEach((k, v) -> sb.append(sb.length() > 1 ? "," : "").append('"').append(k).append("\":").append(toJson(v)));
            return sb.append('}').toString();
        }
        if (o instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (Object v : l) {
                sb.append(sb.length() > 1 ? "," : "").append(toJson(v));
            }
            return sb.append(']').toString();
        }
        if (o instanceof Number || o instanceof Boolean) {
            return String.valueOf(o);
        }
        return '"' + String.valueOf(o).replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
