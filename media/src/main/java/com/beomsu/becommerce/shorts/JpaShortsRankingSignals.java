package com.beomsu.becommerce.shorts;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@link ShortsRankingSignals}의 실제 구현 — {@code short_view_events}·{@code short_videos}를 읽는다. */
@Component
class JpaShortsRankingSignals implements ShortsRankingSignals {

    /**
     * 상품 선호(R28 신호 3)를 계산할 때 되짚어 보는 시청자의 최근 관심 영상 수 상한. 전체 이력을
     * 다 훑으면 느려지고, 너무 적으면 선호가 금방 바뀌어 신호가 흔들린다 — 운영 상식선으로 50을
     * 잡는다(가정, 실측 전).
     */
    static final int ENGAGEMENT_HISTORY_LIMIT = 50;

    private final ShortViewEventRepository eventRepository;
    private final ShortVideoRepository videoRepository;

    JpaShortsRankingSignals(ShortViewEventRepository eventRepository, ShortVideoRepository videoRepository) {
        this.eventRepository = eventRepository;
        this.videoRepository = videoRepository;
    }

    @Override
    public Snapshot load(List<Long> candidateVideoIds, ViewerIdentity viewer) {
        Map<Long, Double> completion = new HashMap<>();
        if (!candidateVideoIds.isEmpty()) {
            for (ShortViewEventRepository.VideoCompletionRate row
                    : eventRepository.completionRates(candidateVideoIds)) {
                completion.put(row.getVideoId(), row.getRate());
            }
        }
        Set<Long> preferred = viewer == null ? Set.of() : preferredProductIds(viewer);
        return new Snapshot(completion, preferred);
    }

    private Set<Long> preferredProductIds(ViewerIdentity viewer) {
        Pageable limit = PageRequest.of(0, ENGAGEMENT_HISTORY_LIMIT);
        List<Long> engagedVideoIds = viewer.isAnonymous()
                ? eventRepository.recentEngagedVideoIdsByAnonymousId(viewer.anonymousId(), limit)
                : eventRepository.recentEngagedVideoIdsByUser(viewer.userId(), limit);
        if (engagedVideoIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> preferred = new LinkedHashSet<>();
        for (ShortVideo video : videoRepository.findAllById(engagedVideoIds)) {
            preferred.addAll(video.getLinkedProductIds());
        }
        return preferred;
    }
}
