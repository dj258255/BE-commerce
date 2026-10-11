package com.beomsu.becommerce.shorts;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 숏폼 시청 신호 기록(R27) — /shorts 화면이 영상마다 보내는 이벤트를 받아 저장한다.
 *
 * <p>영상 존재 여부를 확인하지 않는다 — 이 저장소의 "논리적 FK" 관례와 같고, 관측 기록이
 * 재생 중인 영상의 생애주기(삭제·상태 변화)에 결합되지 않게 한다(ADR-043과 같은 판단).
 */
@Service
@Transactional
public class ShortsViewSignalService {

    private final ShortViewEventRepository repository;
    private final Clock clock;

    @Autowired
    public ShortsViewSignalService(ShortViewEventRepository repository) {
        this(repository, Clock.systemUTC());
    }

    ShortsViewSignalService(ShortViewEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public void record(long shortVideoId, ViewerIdentity viewer, int watchSeconds, boolean completed,
            int replayCount, boolean skippedWithin3s, boolean productTagTapped) {
        if (watchSeconds < 0) {
            throw ShortsException.invalidViewSignal("watchSeconds는 0 이상이어야 합니다: " + watchSeconds);
        }
        if (replayCount < 0) {
            throw ShortsException.invalidViewSignal("replayCount는 0 이상이어야 합니다: " + replayCount);
        }
        Instant occurredAt = clock.instant();
        repository.save(ShortViewEvent.record(shortVideoId, viewer, watchSeconds, completed, replayCount,
                skippedWithin3s, productTagTapped, occurredAt));
    }
}
