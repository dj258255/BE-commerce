# ADR-075. 캐시 채우기와 무효화의 경합 — lease 없음 대 lease 대 버전 비교

- 상태: 채택(비교 실험, 프로덕션 미배선)
- 날짜: 2026-09-28
- 관련: [ADR-070](ADR-070-cache-expiry-stampede-single-flight.md)(같은 파일의 만료 몰림 실험), `FacetCache`(ADR-044), 이슈 #428, `CacheStampedeExperimentTest#versionCheckPreventsStaleSetButNotSameVersionBug`

## 맥락

Memcache lease 원 논문(NSDI 2013)은 "two problems: stale sets and thundering herds"를 함께 푼다. `CacheStampedeExperimentTest`(#418, ADR-070)는 그중 thundering herd(만료 순간 동시 로드 몰림)만 다뤘다. 이 실험은 나머지 하나인 stale set을 다룬다 — 캐시 무효화가 먼저 오고, 그 전에 시작된 느린 캐시 채우기(로더)가 나중에 끝나 옛 값을 write-back 하면 그 값이 다음 만료까지 캐시에 남는다.

Meta "Cache made consistent"(같은 블로그, 직접 확인)는 실제 사고를 밝혔다 — 캐시에 "metadata=0 @version 4", DB에 "metadata=1 @version 4"가 무한히 남았다. 원인은 오류 처리의 "버전이 지정값보다 작으면 캐시 항목을 지운다"가 같은 버전의 틀린 항목을 못 잡은 것이다. 이 실험은 그 실패 모드까지 재현했다.

## 측정 방법

`CacheStampedeExperimentTest`(`@Tag("experiment")`, 순수 JUnit, Docker 불필요, `FacetCache`에 배선하지 않음)에 새 테스트를 더했다. 로더 하나(무효화 이전 버전을 읽고 100ms 뒤 write-back)와 무효화 하나(DB와 캐시를 함께 새 값으로 씀)를 동시에 돌려, 무효화 시각을 0~200ms(로더 지연의 2배) 사이 10ms 간격 21개 트라이얼로 스캔했다. 무효화가 로드 완료(100ms) 전에 시작된 트라이얼(10개)만 "경합"으로 세고, 그 중 최종 캐시가 DB와 다른 비율(불일치율)을 봤다.

- **NO_LEASE**: write-back이 버전 확인 없이 무조건 덮어쓴다.
- **LEASE**: 동시 로드 중복 제거(single-flight)만 있고 write-back에 버전 확인이 없다. 이 실험엔 로더가 하나뿐이라 중복 제거가 개입할 일이 없어, 코드 경로가 NO_LEASE와 같다(의도된 관찰 — 다른 로더가 있었어도 write-back 시점 문제는 그대로다).
- **VERSION_CHECK**: write-back이 "읽은 버전이 현재 캐시 버전보다 낮으면 거부"한다. 정상 무효화(버전이 오름)를 가정한다.
- **VERSION_CHECK_SAME_VERSION_BUG**: 같은 버전 검사를 쓰지만 무효화가 버전을 안 올리고 값만 바꾼다(Meta 사고 재현).

판정 기준은 측정 전 [이슈 #428](https://github.com/dj258255/BE-commerce/issues/428)에 적었다.

## 실측 결과

| 조건 | 경합 트라이얼 | 불일치 건수 | 불일치율 |
|---|---:|---:|---:|
| NO_LEASE | 10/21 | 10 | 1.00 |
| LEASE | 10/21 | 10 | 1.00 |
| VERSION_CHECK | 10/21 | 0 | 0.00 |
| VERSION_CHECK_SAME_VERSION_BUG | 10/21 | 10 | 1.00 |

## 가설 판정

측정 전 이슈 #428에 적은 네 가지 전부 맞았다.

1. **맞았다.** NO_LEASE의 불일치율이 1.00(경합 트라이얼 전부)이다.
2. **맞았다.** LEASE의 불일치율(1.00)이 NO_LEASE(1.00)와 완전히 같다 — lease가 막는 건 중복 로드(thundering herd)이지 무효화-채우기 순서 경합(stale set)이 아니라는 것을 코드 경로 자체가 보여준다(로더가 하나뿐이라 dedup이 작동할 일이 없었다).
3. **맞았다.** VERSION_CHECK의 불일치율이 0.00으로 NO_LEASE·LEASE(1.00)보다 뚜렷이 낮다. 버전이 오르는 정상 무효화에서는 write-back의 낮은 버전이 매번 거부됐다.
4. **맞았다.** 같은 버전으로 값만 바꾸는 무효화(Meta 사고 재현)에서는 VERSION_CHECK도 불일치율 1.00으로, 정상 무효화 때(0.00)보다 뚜렷이 높다 — "버전이 낮으면 거부"가 같은 버전의 다른 값은 구분하지 못한다는 것을 그대로 재현했다.

## 이 실험이 답하지 못하는 것

- 실제 Memcache/Redis 프로토콜의 토큰·리스 만료 세부 규칙은 재현하지 않았다. write-back 시점의 버전 비교 하나로 근사했다.
- ten nines 같은 가용성 수치는 재지 않았다 — 불일치율 하나만 봤다.
- `FacetCache`(ADR-044)에 실제로 배선하지 않았다.
- 왜 어떤 무효화가 버전을 안 올리는지(애플리케이션 버그의 원인)는 재현하지 않았다. "버전을 안 올리는 무효화가 있으면 어떻게 되는가"만 모사했다.
- Meta의 실제 수정(버전 비교 로직을 값 비교로 보강하거나 "같은 버전 다른 값"을 별도로 감지하는 방법)은 설계·측정하지 않았다 — 실패 모드를 재현하는 데서 멈췄다.
