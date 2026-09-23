package com.beomsu.becommerce.order.catalog.search;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * MySQL InnoDB FULLTEXT 자연어 모드. 관련도 점수를 내므로 점수순이다.
 *
 * <p><b>인덱스가 있어야 돈다</b> — {@code FULLTEXT(name, product_type, description)}. 마이그레이션으로 넣지
 * 않았다: 이 방식을 채택하지 않으면 쓰기마다 FULLTEXT 갱신 비용만 남기 때문이다. 실측 스크립트가
 * 인덱스를 만들고 지운다(ADR-051).
 *
 * <p>어간 추출·오타 허용·동의어가 없다. 기본 파서는 공백으로 자르고, 최소 토큰 길이(3)보다 짧은 말은 버린다.
 */
public class FulltextProductSearch implements ProductSearch {

    private static final String MATCH = "MATCH(name, product_type, description) AGAINST (:q IN NATURAL LANGUAGE MODE)";

    private final NamedParameterJdbcTemplate jdbc;

    public FulltextProductSearch(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SearchPage search(String query, int page, int size) {
        Map<String, Object> params = Map.of("q", query, "size", size, "offset", page * size);
        List<Long> ids = jdbc.queryForList(
                "SELECT product_id FROM products WHERE " + MATCH
                        + " ORDER BY " + MATCH + " DESC, product_id LIMIT :size OFFSET :offset",
                params, Long.class);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE " + MATCH, params, Long.class);
        return new SearchPage(ids, total == null ? 0 : total);
    }

    @Override
    public String engine() {
        return "fulltext";
    }

    @Override
    public boolean ranksByRelevance() {
        return true;
    }
}
