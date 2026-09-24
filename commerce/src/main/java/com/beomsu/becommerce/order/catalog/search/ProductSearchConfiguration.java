package com.beomsu.becommerce.order.catalog.search;

import com.beomsu.becommerce.order.catalog.ProductRepository;
import com.beomsu.becommerce.order.catalog.StockRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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

    /** 엔진 구현(예: {@link RefreshingLuceneSearch})도 빈이라 {@code ProductSearch} 가 둘이다. 주입은 물러서기까지 감싼 이쪽이다. */
    @Bean
    @Primary
    ProductSearch productSearch(@Value("${app.catalog.search.engine:lucene}") String engine,
                                @Value("${app.catalog.search.engine-url:http://localhost:9200}") String engineUrl,
                                @Value("${app.catalog.search.index:products}") String index,
                                @Value("${app.catalog.search.timeout:300ms}") Duration timeout,
                                @Value("${app.catalog.search.filters-in-engine:true}") boolean filtersInEngine,
                                ProductRepository products,
                                StockRepository stock,
                                NamedParameterJdbcTemplate jdbc,
                                ObjectProvider<RefreshingLuceneSearch> lucene,
                                MeterRegistry registry) {
        LikeProductSearch likeFields = new LikeProductSearch(products, true);
        CandidateFiltering candidates = new CandidateFiltering(products, stock);
        ProductSearch chosen = switch (engine) {
            case "like" -> new LikeProductSearch(products, false);
            case "like-fields" -> likeFields;
            case "fulltext" -> new FallbackProductSearch(new FulltextProductSearch(jdbc), likeFields, registry);
            case "lucene" -> new FallbackProductSearch(lucene.getObject(), likeFields, candidates, registry);
            case "elasticsearch", "opensearch" -> new FallbackProductSearch(
                    new HttpEngineProductSearch(restClient(engineUrl, timeout), index, engine), likeFields, candidates,
                    registry);
            default -> throw new IllegalStateException("모르는 검색 엔진: " + engine
                    + " (like · like-fields · fulltext · lucene · elasticsearch · opensearch)");
        };
        if (!filtersInEngine && chosen.filtersInEngine()) {
            // 실측용: 같은 엔진에서 필터·패싯만 후보 자르기로 돌린다(#244)
            chosen = new CandidatesOnly(chosen);
        }
        log.info("상품 검색 엔진={} 필터·패싯={}", chosen.engine(), chosen.filtersInEngine() ? "엔진 안" : "후보 자르기");
        return chosen;
    }

    @Bean
    CatalogDocs catalogDocs(NamedParameterJdbcTemplate jdbc) {
        return new CatalogDocs(jdbc);
    }

    /**
     * 기동 때 카탈로그 전체를 읽어 메모리 색인을 만들고, 주기마다 새로 만들어 갈아 끼운다. 변경 반영({@code cdc.enabled})을 켜면
     * 바뀐 상품만 {@code lucene-nrt-refresh} 주기로 보이게 한다(#246). 끄면 검색기를 다시 열 일이 없어 주기를 0 으로 둔다.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.catalog.search.engine", havingValue = "lucene", matchIfMissing = true)
    RefreshingLuceneSearch luceneSearch(CatalogDocs docs,
                                        @Value("${app.catalog.search.lucene-refresh:10m}") Duration refresh,
                                        @Value("${app.catalog.search.cdc.enabled:false}") boolean cdc,
                                        @Value("${app.catalog.search.lucene-nrt-refresh:1s}") Duration nrtRefresh,
                                        MeterRegistry registry) {
        return new RefreshingLuceneSearch(docs::all, docs::byIds, refresh, cdc ? nrtRefresh : Duration.ZERO, registry);
    }

    /** 엔진이 느리면 기다리지 않는다 — 제한 시간을 넘기면 실패로 보고 DB 로 물러선다. */
    private static RestClient restClient(String baseUrl, Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
