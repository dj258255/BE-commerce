# ADR-083. 숏폼 시청 신호는 media 자신의 표에 남긴다 — 커머스의 개인화 Kafka/CDC 경로를 재사용하지 않는다

- 상태: **채택**
- 날짜: 2026-10-08
- 관련: `ShortViewEvent`, `ShortsViewSignalService`, `ShortsFeedRanker`, `V79__short_view_events.sql`,
  [ADR-043](ADR-043-home-impression-log.md), [ADR-047](ADR-047-cdc-for-activity-not-outbox.md),
  [ADR-080](ADR-080-media-gradle-submodule.md), [docs/shorts-feed-ranking.md](../shorts-feed-ranking.md)

## 맥락

R27은 "/shorts 화면의 시청 신호를 기록하고, **기존 개인화 파이프라인이 읽을 수 있는 경로**로
발행하라"고 요구한다. 이 저장소에는 이미 "개인화 이벤트 경로"라고 부를 만한 것이 하나 있다 —
`com.beomsu.becommerce.personalization`(commerce) 모듈의 `UserActivity` → `user_activities`
테이블 → (설정에 따라) Kafka 토픽 `user.activity` → `KafkaContextTransport`로 이어지는 경로다.
그래서 먼저 그 경로를 그대로 쓸 수 있는지 읽었다.

## 결정

**재사용하지 않는다.** 대신 ADR-043(홈 노출 로그)의 선례를 media 모듈 자신의 표
(`short_view_events`)로 따른다 — 요청(시청 세션) 단위 한 행, Outbox/Kafka에 태우지 않음, 기록
실패가 화면을 막지 않음. R28의 점수 계산(완료율·상품 선호 일치)은 media 안에서 이 표를 직접
읽는다 — commerce의 개인화 모듈을 거치지 않는다.

## 근거 — 기존 경로를 못 쓰는 세 가지 구체적 증거

1. **합성 데이터 전용으로 못박혀 있다.** `UserActivity`의 javadoc: "실제 사용자 행동이 아니라
   부하 생성기가 흘린 것". 저장되는 모든 행의 `source`는 상수 `UserActivity.SOURCE_SYNTHETIC`로
   하드코딩돼 있다 — 실데이터를 넣을 수 있는 입력 지점이 아예 없다.
2. **쓰기 엔드포인트 자체가 "실제 화면이 부르는 표면이 아니다"로 문서화돼 있다.**
   `PersonalizationController.record`(`POST /api/v1/personalization/activity`)의 javadoc:
   "합성 생성기용이다 — 실제 화면이 부르는 표면이 아니다." 게다가 `hasRole("USER")`로 잠겨 있어
   R27이 요구하는 비로그인 익명 식별자 시청을 담을 수 없고, 허용된 활동 유형도 `CLICK`·`VIEW`
   둘뿐이라 시청시간·완료·다시보기·건너뛰기·상품탭을 표현할 수 없다.
3. **파이썬 쪽 `personalization/`도 이 신호를 읽는 자리가 아니다.** 그 디렉터리는 H&M·
   Amazon_Fashion 공개 데이터셋을 쓰는 오프라인 학습·실험 파이프라인이고
   (`personalization/docs/01-architecture.md` §6: "실사용자가 없다"), 숏폼의 실시간 시청
   신호를 소비하는 코드가 없다.

세 증거를 종합하면 "기존 개인화 파이프라인이 읽을 수 있는 경로"는 **커머스 쪽 Kafka/CDC
경로가 아니라, 이 저장소가 관측 데이터에 실제로 적용해 온 패턴**(ADR-043)을 의미한다고 읽는
것이 더 정확하다 — 그래서 그 패턴을 따랐다.

## 대안과 버린 이유

| 대안 | 왜 버렸나 |
| --- | --- |
| **`user_activities`에 `activityType`을 늘려 그대로 씀** | `source`가 영원히 `SYNTHETIC`로 하드코딩돼 있어 실데이터를 구분할 길이 없고, 그 표는 "E1의 이벤트 원천이자 E2가 재생할 합성 로그"라는 다른 실험(온라인 컨텍스트 신선도 측정)의 입력이라 섞으면 그 실험 자체가 오염된다 |
| **`PersonalizationController`에 엔드포인트 추가** | 인가가 `ROLE_USER`라 비로그인을 포기해야 하고, media가 commerce 내부 모듈에 쓰기 의존을 갖게 돼 R32(media↔commerce 경계)를 되돌린다 |
| **Outbox(Spring Modulith Event Publication Registry)로 발행** | 시청 신호는 도메인 상태가 아니라 관측 데이터다. ADR-043이 이미 같은 판단을 내렸고 그 이유(발행량에 비례해 자라는 구조를 관측에 쓰지 않는다)를 반복할 뿐이다 |
| **Kafka로 직접 발행** | 브로커 없는 기본 실행에서 기록이 사라진다(ADR-043과 같은 이유) |

## 대가 (정직하게)

- **"개인화 파이프라인"이라는 말의 두 의미가 이 저장소 안에 공존한다** — commerce의 실험용
  컨텍스트 파이프라인(합성)과, 이번에 만든 media의 관측·랭킹(R28)은 서로 다른 표·다른 목적이다.
  나중에 "숏폼도 홈처럼 실사용자 컨텍스트에 반영하자"가 되면, 그때는 `user_activities`의
  `source`를 실데이터용으로 분리하는 설계가 먼저 필요하다(지금은 범위 밖).
- **표가 자란다** — ADR-043의 `home_impressions`와 같은 문제다. 보존 정책(배치 삭제)을 아직
  붙이지 않았다 — 지금은 쓰임이 생기기 전이라 재지 않았다(ADR-043과 같은 판단, 다시 볼 조건에
  남긴다).
- **상품 선호 일치 신호가 자기 자신의 과거 완료 기록을 포함한다** — 지금 보고 있는 영상을
  과거에 완료한 적이 있으면 그 자체가 선호 집합에 들어가 약간의 자기강화가 생긴다. 영향은
  작다(상품 겹침 비율 하나일 뿐이고 가중치도 0.2) — 문제가 커지면 "지금 후보는 선호 집합
  계산에서 뺀다"로 고친다.
