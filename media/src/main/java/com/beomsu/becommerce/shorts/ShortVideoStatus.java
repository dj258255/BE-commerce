package com.beomsu.becommerce.shorts;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 숏폼 영상 상태머신(R22) — UPLOADING → UPLOADED → PROBING → TRANSCODING → READY, 실패는
 * FAILED를 거쳐 재시도(PROBING 재진입)하거나 재시도를 소진하면 QUARANTINED로 격리된다.
 *
 * <p>허용된 전이만 {@link #TRANSITIONS}에 선언하고 그 밖의 전이는 {@link #canTransitionTo}가
 * false를 반환해 {@link ShortVideo}가 막는다({@code OrderStatus}와 동일한 가드 방식).
 */
public enum ShortVideoStatus {

    /** presigned URL로 파일이 올라가는 중 — 아직 서버가 완료를 확인하지 못했다. */
    UPLOADING,
    /** 업로드 완료 알림을 받은 직후 — 아직 점검 전. */
    UPLOADED,
    /** 길이·크기·세로 비율 등 메타데이터 점검 중. */
    PROBING,
    /** 세 화질 HLS·썸네일로 변환 중. */
    TRANSCODING,
    /** 변환 완료 — 피드에 노출 가능(terminal). */
    READY,
    /** probe 또는 변환 실패 — 재시도 소진 전까지는 종료 상태가 아니다. */
    FAILED,
    /** 재시도(3회)를 소진해 자동 처리에서 격리됨(terminal) — 운영 개입 대상. */
    QUARANTINED;

    private static final Map<ShortVideoStatus, Set<ShortVideoStatus>> TRANSITIONS = Map.of(
            UPLOADING,   EnumSet.of(UPLOADED),
            UPLOADED,    EnumSet.of(PROBING),
            PROBING,     EnumSet.of(TRANSCODING, FAILED),
            TRANSCODING, EnumSet.of(READY, FAILED),
            FAILED,      EnumSet.of(PROBING, QUARANTINED),
            READY,       Collections.emptySet(),
            QUARANTINED, Collections.emptySet()
    );

    public boolean canTransitionTo(ShortVideoStatus target) {
        return TRANSITIONS.getOrDefault(this, Collections.emptySet()).contains(target);
    }
}
