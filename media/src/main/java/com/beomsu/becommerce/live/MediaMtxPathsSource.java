package com.beomsu.becommerce.live;

import java.util.Set;

/**
 * MediaMTX Control API에서 "지금 ready(퍼블리셔가 붙어 흐르는 중)"인 {@code live/*} 경로의
 * 스트림 키 집합을 읽는 경계(R3) — {@link MediaMtxPathPoller}가 매 주기 부르는 단 하나의
 * 외부 의존이다. 공식 {@code bluenviron/mediamtx} 이미지에 셸이 없어 명령 훅
 * (runOnReady/runOnNotReady)을 쓸 수 없다는 것을 실제로 확인하고(ADR-082), push(훅)를
 * poll(이 인터페이스)로 바꿨다 — MediaMTX 자체 HTTP 서버 기능이라 셸이 필요 없다.
 *
 * <p>구현({@link RestClientMediaMtxPathsSource})과 분리해 두는 이유: 테스트가 실제 HTTP
 * 호출 없이 "이번 주기엔 이 키들이 ready"를 바로 주입할 수 있게 하기 위해서다(실패·지연
 * 주입도 같은 자리에서 — R29 폴백 테스트와 같은 패턴).
 */
interface MediaMtxPathsSource {

    /**
     * 지금 ready인 {@code live/*} 경로들의 스트림 키. 실패하면(연결 안 됨 등) 예외를 던진다 —
     * 호출자({@link MediaMtxPathPoller})가 그 주기를 통째로 건너뛰는 판단을 한다.
     */
    Set<String> readyStreamKeys();
}
