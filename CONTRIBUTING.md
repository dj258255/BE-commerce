# 기여 규칙

이 저장소는 결제 도메인의 정합성을 다룬다. 변경은 **동작보다 근거를 먼저** 남긴다.

## 작업 단위

작업은 이슈 하나 = 브랜치 하나 = PR 하나로 자른다. 이슈 번호와 PR 번호는 같은 시퀀스를 공유한다.

| 단계 | 규칙 |
| --- | --- |
| 이슈 | 배경 / 할 일 / 검증 기준을 적는다. 마일스톤과 라벨을 붙인다 |
| 브랜치 | `feat/…`, `fix/…`, `docs/…`, `chore/…`, `refactor/…` |
| PR | 본문에 `Closes #NN`으로 이슈를 닫는다. CI가 초록이어야 머지한다 |

## 커밋

- 한국어로 쓴다. 제목은 **무엇을 했는지** 한 줄, 본문은 불릿으로 **왜**를 적는다.
- 이모지와 도구·에이전트 언급은 쓰지 않는다.
- 설계 결정은 커밋 로그에 흩어 두지 않는다. 트레이드오프가 있는 결정은 ADR 한 편으로 남기고 커밋에서 참조한다.

```text
정산 배치가 하루 500건에 묶여 있던 것을 푼다

- 처리 상한을 설정으로 꺼내 배치 크기와 분리
- 상한 도달 시 다음 틱으로 이월, 유실 없음을 테스트로 고정
```

## 검증

응답 코드만 보고 끝내지 않는다. 상태가 실제로 확정됐는지 DB를 재조회한다.

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.9/libexec/openjdk.jdk/Contents/Home
./gradlew clean test --console=plain          # 기본 스위트(chaos/integration 태그 제외)
./gradlew -p consumer-app build               # 독립 프로젝트
```

- 문서와 코드가 갈라지면 테스트가 먼저 막는다 — `ErdDocMatchesSchemaTest`(스키마↔ERD),
  `ApiSpecErrorCodesTest`(에러 코드↔API 스펙), `ModularityTests`(모듈 경계). 셋을 함께 고친다.
- 실 인프라가 필요한 검증은 별도 태스크로 분리한다(`integrationTest`, `chaosTest`, `bench`).
- 스키마를 바꾸면 Flyway 마이그레이션을 추가하고 `ddl-auto=validate`로 실기동을 확인한다.

## 문서

- 구현된 사실, 측정 결과, 아직 하지 않은 계획을 구분해 적는다.
- 결정이 바뀌면 이전 기록을 지우지 않고 상단 상태와 후속 ADR로 연결한다.
- 성능 수치에는 환경·부하 모델·표본 크기를 함께 적는다.
