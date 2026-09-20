package com.beomsu.becommerce.timeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 주문 하나의 전 과정을 시간순으로 조립한다 (ADR-011).
 *
 * <p>기여자들을 모아 정렬만 한다. <b>도메인 지식을 갖지 않는다</b> — 무엇이 중요한 사건인지,
 * 어떻게 요약할지는 각 도메인이 정한다.
 *
 * <p><b>한 기여자가 실패해도 전체를 버리지 않는다.</b> 대사 원인을 판단하려는 사람에게
 * "정산 조회가 실패해서 아무것도 못 보여준다"는 최악의 응답이다. 나머지 열 곳의 사실만으로도
 * 대개 판단이 된다. 대신 <b>무엇이 빠졌는지는 반드시 알린다</b> — 조용히 빠지면 사람이
 * "정산 기록이 없다"고 잘못 읽는다. 이 구분이 이 클래스에서 제일 중요하다.
 */
@Service
public class OrderTimelineService {

    private static final Logger log = LoggerFactory.getLogger(OrderTimelineService.class);

    private final List<TimelineContributor> contributors;

    OrderTimelineService(List<TimelineContributor> contributors) {
        this.contributors = contributors;
    }

    /**
     * 읽기 전용 트랜잭션 하나로 묶는다 — 기여자들이 각자 커넥션을 잡지 않게 하고,
     * 조립 도중 다른 트랜잭션이 끼어들어 <b>앞뒤가 안 맞는 타임라인</b>이 나오는 것을 막는다.
     */
    @Transactional(readOnly = true)
    public OrderTimeline assemble(String orderNo) {
        List<TimelineEntry> entries = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (TimelineContributor contributor : contributors) {
            try {
                entries.addAll(contributor.contribute(orderNo));
            } catch (RuntimeException e) {
                // 하나가 죽어도 나머지는 살린다. 다만 빠졌다는 사실은 응답에 실어 보낸다.
                failed.add(contributor.source().name());
                log.warn("타임라인 기여자 실패 source={} orderNo={}", contributor.source(), orderNo, e);
            }
        }

        entries.sort(Comparator.comparing(TimelineEntry::at)
                .thenComparing(e -> e.source().name()));   // 같은 시각이면 출처 이름으로 안정 정렬
        return new OrderTimeline(orderNo, entries, failed);
    }
}
