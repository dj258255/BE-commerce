package com.beomsu.becommerce.order.catalog.search;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 검색어 고치기(#260)를 DB 의 라벨로 만든다. 켜져 있지 않으면 검색어를 그대로 돌려준다.
 *
 * <p>라벨은 처음 쓸 때 한 번 읽는다(색상·중분류 라벨은 카탈로그 적재 때만 바뀐다).
 */
@Component
public class QueryRewriting {

    private final NamedParameterJdbcTemplate jdbc;
    private final boolean enabled;
    private final MeterRegistry registry;
    private volatile QueryRewriter rewriter;

    public QueryRewriting(NamedParameterJdbcTemplate jdbc,
                          @Value("${app.catalog.search.rewrite.enabled:false}") boolean enabled, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.registry = registry;
    }

    public QueryRewriter.Rewritten rewrite(String query) {
        if (!enabled) {
            return new QueryRewriter.Rewritten(query, null, java.util.List.of(), false);
        }
        QueryRewriter.Rewritten r = rewriter().rewrite(query);
        registry.counter("catalog.search.rewrite", "changed", String.valueOf(r.changed())).increment();
        return r;
    }

    private QueryRewriter rewriter() {
        QueryRewriter r = rewriter;
        if (r == null) {
            synchronized (this) {
                if (rewriter == null) {
                    rewriter = load();
                }
                r = rewriter;
            }
        }
        return r;
    }

    private QueryRewriter load() {
        Map<String, String> colours = new LinkedHashMap<>();
        jdbc.getJdbcTemplate().query("SELECT DISTINCT colour_name, colour_code FROM products "
                        + "WHERE colour_name IS NOT NULL AND colour_code IS NOT NULL",
                rs -> {
                    colours.putIfAbsent(rs.getString(1), rs.getString(2));
                });
        Map<String, Set<String>> english = new LinkedHashMap<>();
        jdbc.getJdbcTemplate().query("SELECT name, source_name FROM categories WHERE parent_code IS NOT NULL AND source_name IS NOT NULL",
                rs -> {
                    english.computeIfAbsent(rs.getString(1), k -> new LinkedHashSet<>()).add(rs.getString(2).toLowerCase());
                });
        return new QueryRewriter(colours, english, QueryRewriter.defaultSynonyms());
    }
}
