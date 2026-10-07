/**
 * 숏폼(shorts) 모듈 — 세로 짧은 영상의 업로드~변환 파이프라인(R21·R22).
 *
 * <p>이번 단계는 {@link com.beomsu.becommerce.shorts.ShortVideo}의 상태 전이
 * (UPLOADING → UPLOADED → PROBING → TRANSCODING → READY/FAILED)와 재시도(3회)·격리만 다룬다.
 * presigned 업로드 저장소·변환 워커·API·media/ 분리는 다음 단계에서 붙는다. 기존 주문·결제·재고
 * 모듈은 호출하지 않는다(allowedDependencies는 shared뿐).
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared" }
)
package com.beomsu.becommerce.shorts;
