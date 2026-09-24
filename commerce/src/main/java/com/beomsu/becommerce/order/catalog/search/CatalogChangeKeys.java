package com.beomsu.becommerce.order.catalog.search;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * CDC 이벤트에서 상품 id 만 꺼낸다. 커넥터가 키를 {@code product_id} 로 만든다({@code cdc/register-catalog-connector.json}).
 *
 * <p>값(바뀐 뒤 행)은 보지 않는다. products 이벤트와 stock 이벤트의 모양이 다르고, 순서가 뒤바뀌거나 두 번 올 수 있다.
 * id 로 DB 를 다시 읽으면 셋 다 신경 쓸 필요가 없다.
 */
final class CatalogChangeKeys {

    private CatalogChangeKeys() {
    }

    static Set<Long> productIds(List<ConsumerRecord<String, String>> records) {
        Set<Long> ids = new LinkedHashSet<>();
        for (ConsumerRecord<String, String> r : records) {
            if (r.key() != null && !r.key().isBlank()) {
                ids.add(Long.parseLong(r.key().replace("\"", "").trim()));
            }
        }
        return ids;
    }
}
