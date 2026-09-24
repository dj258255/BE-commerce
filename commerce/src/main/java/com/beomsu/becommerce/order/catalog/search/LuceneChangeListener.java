package com.beomsu.becommerce.order.catalog.search;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;

/**
 * 상품·재고 변경(CDC)을 받아 <b>이 인스턴스의</b> Lucene 색인에 반영한다(#246).
 *
 * <p><b>인스턴스마다 모든 변경을 받는다.</b> 색인이 인스턴스마다 있으므로 컨슈머 그룹을 나눠 가지면 한 인스턴스만
 * 반영한다. 그래서 그룹 id 를 부팅마다 새로 짓는다. 대가: 인스턴스 수만큼 같은 변경을 읽고 DB 를 다시 읽는다.
 *
 * <p><b>어디서부터 읽나</b>: 새 그룹이라 커밋된 위치가 없다. 기동 색인은 적재를 시작한 순간의 DB 를 담으므로,
 * 그 순간보다 {@link #SEEK_MARGIN_MS} 앞의 이벤트부터 읽는다. 이미 색인에 든 변경을 다시 받아도 DB 를 다시 읽을 뿐이라
 * 결과가 같다. 뒤에서 시작하면 적재와 구독 사이의 변경을 잃는다.
 *
 * <p><b>이 리스너만 {@code auto.offset.reset=latest}</b> 다. 그 시각 뒤에 레코드가 없으면 시각으로 찾기가 위치를 못 정하고,
 * 앱 전역 값({@code earliest})으로 떨어져 토픽 전체를 처음부터 다시 읽었다(#246 실측: 변경 없이 기동했는데 2,299건 반영).
 * {@code latest} 면 그때는 끝에서 시작한다. 뒤에 레코드가 있으면 시각으로 찾은 위치가 이긴다.
 */
@Component
@Profile("kafka")
@ConditionalOnExpression("${app.catalog.search.cdc.enabled:false} and '${app.catalog.search.engine:lucene}' == 'lucene'")
class LuceneChangeListener implements ConsumerSeekAware {

    private static final Logger log = LoggerFactory.getLogger(LuceneChangeListener.class);

    /** 적재 시작 시각과 binlog 시각(커밋 시각) 사이의 어긋남을 덮는 여유. */
    static final long SEEK_MARGIN_MS = 5_000;

    private final RefreshingLuceneSearch lucene;

    LuceneChangeListener(RefreshingLuceneSearch lucene) {
        this.lucene = lucene;
    }

    @KafkaListener(topics = "${app.catalog.search.cdc.topic:catalog.change}",
            groupId = "catalog-search-lucene-${random.uuid}", batch = "true",
            properties = "auto.offset.reset=latest")
    void on(List<ConsumerRecord<String, String>> records) {
        lucene.changed(CatalogChangeKeys.productIds(records));
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        long from = lucene.loadStartedAt() - SEEK_MARGIN_MS;
        log.info("카탈로그 변경 구독: {} 를 {} 부터 읽는다", assignments.keySet(), Instant.ofEpochMilli(from));
        callback.seekToTimestamp(assignments.keySet(), from);
    }
}
