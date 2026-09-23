package com.beomsu.becommerce.order.catalog.search;

import com.beomsu.becommerce.order.catalog.ProductRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * 검색 구현을 설정 하나({@code app.catalog.search.engine})로 고른다(ADR-051).
 *
 * <p>기본값은 {@code lucene} 이다(ADR-051 의 실측으로 골랐다). 엔진(fulltext·lucene·elasticsearch·opensearch)은 모두
 * {@link FallbackProductSearch} 로 감싸 실패하면 {@code like-fields} 로 물러선다.
 */
@Configuration
public class ProductSearchConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ProductSearchConfiguration.class);

    @Bean
    ProductSearch productSearch(@Value("${app.catalog.search.engine:lucene}") String engine,
                                @Value("${app.catalog.search.engine-url:http://localhost:9200}") String engineUrl,
                                @Value("${app.catalog.search.index:products}") String index,
                                @Value("${app.catalog.search.timeout:300ms}") Duration timeout,
                                @Value("${app.catalog.search.lucene-refresh:10m}") Duration luceneRefresh,
                                ProductRepository products,
                                NamedParameterJdbcTemplate jdbc,
                                MeterRegistry registry) {
        LikeProductSearch likeFields = new LikeProductSearch(products, true);
        ProductSearch chosen = switch (engine) {
            case "like" -> new LikeProductSearch(products, false);
            case "like-fields" -> likeFields;
            case "fulltext" -> new FallbackProductSearch(new FulltextProductSearch(jdbc), likeFields, registry);
            case "lucene" -> new FallbackProductSearch(lucene(jdbc, luceneRefresh, registry), likeFields, registry);
            case "elasticsearch", "opensearch" -> new FallbackProductSearch(
                    new HttpEngineProductSearch(restClient(engineUrl, timeout), index, engine), likeFields, registry);
            default -> throw new IllegalStateException("모르는 검색 엔진: " + engine
                    + " (like · like-fields · fulltext · lucene · elasticsearch · opensearch)");
        };
        log.info("상품 검색 엔진={}", chosen.engine());
        return chosen;
    }

    /** 기동 때 카탈로그 전체를 읽어 메모리 색인을 만들고, 주기마다 새로 만들어 갈아 끼운다. */
    private static RefreshingLuceneSearch lucene(NamedParameterJdbcTemplate jdbc, Duration refresh, MeterRegistry registry) {
        return new RefreshingLuceneSearch(() -> jdbc.getJdbcTemplate().query(
                "SELECT product_id, name, product_type, description FROM products",
                (rs, i) -> new LuceneProductSearch.Doc(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))),
                refresh, registry);
    }

    /** 엔진이 느리면 기다리지 않는다 — 제한 시간을 넘기면 실패로 보고 DB 로 물러선다. */
    private static RestClient restClient(String baseUrl, Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
