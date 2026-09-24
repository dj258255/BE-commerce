package com.beomsu.becommerce.order.catalog.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;

/**
 * 앱 안 Lucene 이 버티는 규모를 잰다(#244). 앱이 쓰는 {@link LuceneProductSearch} 를 그대로 만든다.
 *
 * <pre>
 *   java -Dloader.main=...LuceneScaleBenchmark -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
 *        scale    catalog.tsv queries.json out.json     # 색인 시간·크기·p95(동시성 10)
 *   ...    matches  catalog.tsv queries.json out.json     # 쿼리마다 필터 없는 전체 일치 집합(정답 계산용)
 * </pre>
 *
 * <p>카탈로그는 {@code tools/search/catalog_dump.py} 의 TSV 다. 규모는 파일이 정한다(실제 행을 복제한 합성 카탈로그).
 * 앱 기동·DB·HTTP 를 빼고 색인과 질의만 잰다 — 앱 경로의 지연은 {@code filter_eval.py} 가 API 로 따로 잰다.
 */
public final class LuceneScaleBenchmark {

    private static final int CONCURRENCY = 10;
    private static final int ROUNDS = 3;

    private LuceneScaleBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        Iterable<LuceneProductSearch.Doc> docs = stream(Path.of(args[1]));
        JsonNode queries = new ObjectMapper().readTree(Path.of(args[2]).toFile());
        Map<String, Object> out = switch (args[0]) {
            case "scale" -> scale(docs, queries);
            case "matches" -> matches(docs, queries);
            default -> throw new IllegalArgumentException(args[0]);
        };
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[3]).toFile(), out);
    }

    private static Map<String, Object> scale(Iterable<LuceneProductSearch.Doc> docs, JsonNode queries) throws Exception {
        System.gc();
        long heapBefore = usedHeap();
        try (LuceneProductSearch lucene = new LuceneProductSearch(docs)) {
            System.gc();
            long heapAfter = usedHeap();
            List<Runnable> work = new ArrayList<>();
            for (JsonNode q : queries) {
                String text = q.get("q").asText();
                SearchFilters f = filters(q.get("filters"));
                work.add(() -> lucene.search(text, 0, 20));
                work.add(() -> lucene.searchFiltered(text, f, 0, 20));
                work.add(() -> lucene.facets(text, f));
            }
            work.forEach(Runnable::run);                           // 워밍업 한 바퀴
            double[] c1 = timed(work, 1);
            List<Double> c10 = new ArrayList<>();
            for (int r = 0; r < ROUNDS; r++) {
                for (double v : timed(work, CONCURRENCY)) {
                    c10.add(v);
                }
            }
            double[] c10a = c10.stream().mapToDouble(Double::doubleValue).toArray();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("docs", lucene.docCount());
            out.put("build_ms", lucene.buildMillis());
            out.put("index_bytes", lucene.ramBytes());
            out.put("heap_delta_bytes", heapAfter - heapBefore);
            out.put("requests_c1", c1.length);
            out.put("c1_p50_ms", pct(c1, 50));
            out.put("c1_p95_ms", pct(c1, 95));
            out.put("requests_c10", c10a.length);
            out.put("c10_p50_ms", pct(c10a, 50));
            out.put("c10_p95_ms", pct(c10a, 95));
            out.put("c10_p99_ms", pct(c10a, 99));
            out.put("max_heap_bytes", Runtime.getRuntime().maxMemory());
            System.out.println(out);
            return out;
        }
    }

    /** 쿼리마다 필터 없는 전체 일치 집합. 정답은 이 집합에 필터를 파이썬에서 직접 적용해 만든다. */
    private static Map<String, Object> matches(Iterable<LuceneProductSearch.Doc> docs, JsonNode queries) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        try (LuceneProductSearch lucene = new LuceneProductSearch(docs)) {
            IndexSearcher searcher = lucene.searcher();
            for (JsonNode q : queries) {
                String text = q.get("q").asText();
                Query query = lucene.toQuery(text);
                List<Long> ids = new ArrayList<>();
                if (query != null) {
                    var top = searcher.search(query, Math.max(1, searcher.count(query)));
                    for (var sd : top.scoreDocs) {
                        ids.add(searcher.storedFields().document(sd.doc).getField("id").numericValue().longValue());
                    }
                }
                out.put(text, ids);
            }
        }
        return out;
    }

    private static double[] timed(List<Runnable> work, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Double>> fs = new ArrayList<>();
            for (Runnable w : work) {
                fs.add(pool.submit(() -> {
                    long s = System.nanoTime();
                    w.run();
                    return (System.nanoTime() - s) / 1e6;
                }));
            }
            double[] ms = new double[fs.size()];
            for (int i = 0; i < ms.length; i++) {
                ms[i] = fs.get(i).get();
            }
            return ms;
        } finally {
            pool.shutdown();
        }
    }

    static SearchFilters filters(JsonNode f) {
        if (f == null || f.isNull()) {
            return SearchFilters.NONE;
        }
        return new SearchFilters(text(f, "category"), null, text(f, "colour"), text(f, "productType"),
                f.hasNonNull("minPrice") ? f.get("minPrice").asLong() : null,
                f.hasNonNull("maxPrice") ? f.get("maxPrice").asLong() : null,
                f.path("inStock").asBoolean(false));
    }

    private static String text(JsonNode f, String key) {
        return f.hasNonNull(key) ? f.get(key).asText() : null;
    }

    /**
     * 파일을 한 줄씩 읽어 넘긴다. 300만 행을 리스트로 올리면 색인이 아니라 입력이 힙을 먹어서, 힙 증가분이
     * 색인 크기를 말해 주지 못한다.
     */
    static Iterable<LuceneProductSearch.Doc> stream(Path tsv) {
        return () -> {
            try {
                BufferedReader r = Files.newBufferedReader(tsv);
                return r.lines().map(LuceneScaleBenchmark::parse).onClose(() -> {
                    try {
                        r.close();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }).iterator();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private static LuceneProductSearch.Doc parse(String line) {
        String[] v = Arrays.copyOf(line.split("\t", -1), 9);
        return new LuceneProductSearch.Doc(Long.parseLong(v[0]), v[1], v[2], v[3],
                blank(v[4]), blank(v[5]), blank(v[6]), Long.parseLong(v[7]), "1".equals(v[8]));
    }

    private static String blank(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static double pct(double[] xs, double p) {
        double[] s = xs.clone();
        Arrays.sort(s);
        return s[Math.min(s.length - 1, (int) Math.round(p / 100 * (s.length - 1)))];
    }
}
