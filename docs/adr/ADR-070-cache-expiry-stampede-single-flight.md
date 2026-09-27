# ADR-070. 인기 상품 캐시 만료 몰림 — lease/single-flight 대 나이브 TTL

- 상태: 채택(비교 실험, 프로덕션 미배선)
- 날짜: 2026-09-28
- 관련: [ADR-044](ADR-044-no-search-engine-yet.md)(`FacetCache`의 나이브 TTL), [ADR-022](ADR-022-pg-brownout-resource-limits.md)(Hikari `maximum-pool-size: 20`), 이슈 #417, `CacheStampedeExperimentTest`

## 맥락

기존 `FacetCache`(ADR-044)는 TTL 만료에 보호가 없다. `get()`이 만료를 보면 호출자마다 각자 `loader`를 부른다. 인기 상품처럼 조회가 몰리는 키가 만료되는 순간, 동시 요청 수만큼 DB 도달 조회가 튈 것이라고 봤다(cache stampede, thundering herd). Meta "Scaling Memcache at Facebook"의 lease는 같은 키에 진행 중인 로드가 있으면 새 로드를 시작하지 않고 그 결과를 나눠 쓴다(이 저장소 기준 용어로는 single-flight).

## 결정할 것

만료 순간 보호가 캐시 신선도(로드를 기다리는 대기 비용)와 DB 보호(도달 조회 수) 사이에서 실제로 남는 장사인지, 그리고 그 이득이 동시성이 커질수록 커지는지.

## 측정 방법

`CacheStampedeExperimentTest`(`@Tag("experiment")`, 순수 JUnit, Docker 불필요)로 `FacetCache`와 같은 로직의 `NaiveTtlCache`와 `SingleFlightTtlCache`를 비교했다. 로더 지연 100ms, TTL 50ms, 동시성 10/50/100. **DB를 유한 자원으로 모사**했다 — 로더를 이 저장소 Hikari 기본값(ADR-022, `maximum-pool-size: 20`)과 같은 크기의 고정 스레드 풀에서 돌려, 동시 로더 호출이 풀보다 많으면 큐잉이 생기게 했다. 무제한 병렬 로더를 쓰면 NAIVE도 SINGLEFLIGHT도 벽시계 지연이 로더 지연과 비슷하게 끝나 DB 과부하라는 실험의 요점이 드러나지 않는다.

판정 기준은 측정 전 [이슈 #417](https://github.com/dj258255/BE-commerce/issues/417)에 적었다.

## 실측 결과 (3회 반복)

| 동시성 | 조건 | 로더(DB) 호출 수 | p50 | p95 | max |
|---:|---|---:|---:|---:|---:|
| 10 | NAIVE | 10 / 10 / 10 | 107~110ms | 109~110ms | 109~110ms |
| 10 | SINGLEFLIGHT | 1 / 1 / 1 | 107ms | 107ms | 107ms |
| 50 | NAIVE | 50 / 50 / 50 | 209~214ms | 318~325ms | 320~326ms |
| 50 | SINGLEFLIGHT | 1 / 1 / 1 | 107~108ms | 108~109ms | 108~109ms |
| 100 | NAIVE | 100 / 100 / 100 | 318~323ms | 536~539ms | 544~545ms |
| 100 | SINGLEFLIGHT | 1 / 1 / 1 | 101~109ms | 102~110ms | 102~110ms |

3회 모두 오차범위 안에서 재현됐다(회차 간 최대 차이 p95 기준 약 10ms). 원자료는 `./gradlew -p commerce experimentTest --tests '*CacheStampede*'` 출력 그대로다(별도 CSV로 남기지 않음 — 6줄×3회, 표로 옮겨 적는 것으로 충분하다고 판단).

## 가설 판정

측정 전 이슈 #417에 적은 네 가지 중 셋은 맞았고 하나는 방향이 반대로 틀렸다.

1. **맞았다, 더 정확하게.** NAIVE의 로더 호출 수가 동시성에 "비례"가 아니라 **정확히 같았다**(±10% 여유를 뒀는데 오차 0). 만료를 본 호출자 전원이 각자 로더를 불렀다는 뜻이다.
2. **맞았다.** SINGLEFLIGHT는 세 동시성 모두 로더 호출 수 1 — 조건이 실제로 섰다는 증거(카운터).
3. **틀렸다(방향이 반대).** "SINGLEFLIGHT의 p95가 NAIVE보다 같거나 크다"고 예상했지만 실측은 반대다 — 동시성 10에서는 거의 같았지만(107 대 109ms), 동시성이 오르면 **SINGLEFLIGHT가 오히려 더 빠르다**(동시성 100에서 NAIVE 536~539ms 대 SINGLEFLIGHT 102~110ms, 약 5배 차이). 이유는 DB를 유한 자원으로 모사했기 때문이다 — NAIVE는 로더 N개가 풀(20) 앞에서 줄을 서 큐잉 지연이 쌓이지만(리틀의 법칙과 같은 모양, ADR-022의 워커 점유와 같은 구조), SINGLEFLIGHT는 로더를 한 번만 부르므로 대기자 전원이 그 한 번의 지연(약 100ms)만 진다. **"기다리는 대가"라고 예상했던 것이 실은 "몰림 자체가 대가"였다** — lease 는 대기 비용을 만드는 게 아니라 없앤다.
4. **맞았다.** DB 도달 호출 감소율(1 - SINGLEFLIGHT/NAIVE)이 동시성 10에서 90%, 50에서 98%, 100에서 99%로 동시성이 커질수록 커졌다.

## 이 실험이 답하지 못하는 것

- **캐시 키가 여러 개일 때의 락 경합**은 재지 않았다(단일 키 만료 몰림만 봤다). `ConcurrentHashMap.computeIfAbsent`가 키별로 다른 버킷이라 키가 흩어지면 이 결과가 그대로 유지될 가능성이 높지만 실측하지 않았다.
- **stale-while-revalidate**(만료돼도 낡은 값을 먼저 주고 백그라운드로 갱신)는 비교에 넣지 않았다. 이 실험의 SINGLEFLIGHT는 대기자를 블로킹하는 방식이고, 그 대안(낡은 값을 먼저 반환)은 최신성 대가는 있지만 대기 지연이 아예 0에 가까울 수 있다 — 이번에 비교한 것보다 나은 선택지일 수 있으나 측정하지 않았다.
- `FacetCache`나 `ItemPoolSource`에 실제로 배선하지 않았다 — 26·27절의 다른 실험과 같은 스코프(비교 근거만 남긴다). 실제로 켜려면 `computeIfAbsent`의 락 경합을 실 트래픽 패턴(카탈로그처럼 키가 많은 경우)으로 다시 재야 한다.
