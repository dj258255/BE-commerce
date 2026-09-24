/**
 * 실험(experiment) 모듈(#256) — A/B 변형을 사용자에게 <b>고정 배정</b>한다.
 *
 * <p>배정은 저장하지 않는다. 실험 이름·솔트·사용자 id 의 해시로 정하므로 같은 입력이면 늘 같은 변형이다. 노출과 귀속은 이 모듈이
 * 하지 않는다 — 노출은 홈이 기록하고(ADR-043), 귀속은 분석 스크립트가 기록을 모아 계산한다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {}
)
package com.beomsu.becommerce.experiment;
