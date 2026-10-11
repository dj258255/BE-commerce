package com.beomsu.becommerce.shorts;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R28 랭킹이 필요로 하는 외부 신호를 한 번에 가져오는 경계(R29 폴백의 단위).
 *
 * <p>{@link ShortsFeedRanker}는 이 인터페이스 호출 하나를 제한 시간 안에서 기다린다 — 실패하거나
 * 느리면 그 호출 전체를 폴백 대상으로 본다. 테스트는 이 인터페이스의 가짜 구현으로 실패·지연을
 * 주입한다(DB 없이, R29).
 */
interface ShortsRankingSignals {

    Snapshot load(List<Long> candidateVideoIds, ViewerIdentity viewer);

    /** 완료율(영상 id별)과, 이 시청자가 과거 관심을 보인 상품 id 집합. */
    record Snapshot(Map<Long, Double> completionRateByVideo, Set<Long> preferredProductIds) {
    }
}
