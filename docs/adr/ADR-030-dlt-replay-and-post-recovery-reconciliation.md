# ADR-030. 격리·발견·복구·검증을 따로 만든다

- 상태: **채택(복구 멱등화 + 복구 후 검증 추가)**. 네 책임을 main 의 실제 구조에 맞춰 정리하고, 비어 있던 자리를 채웠다.
- 날짜: 2026-09-19
- 관련: `NotificationService`, `NotificationAdminService`, `DeadLetterAdminController`, [ADR-024](ADR-024-settlement-extraction-data-cutover.md), `consumer-app`(Kafka DLT)

## 맥락

[ADR-024](ADR-024-settlement-extraction-data-cutover.md) 의 결정 9 는 DLT 격리를 **"대사가 최종 방어선이라 이탈이 유실이 아니다"** 라는 근거로 골랐다. **그 근거는 발견까지만 보장한다.** 격리된 메시지를 모아 두고 대사가 불일치로 잡아도, **복구를 손으로 하는 동안 발견과 복구 사이가 사람의 속도로 벌어진다.**

네 가지는 다른 책임이다.

| 책임 | 내용 | 로드맵이 본 main |
|---|---|---|
| **격리** | 실패 메시지를 보존한다 | 있다 (DLQ) |
| **발견** | 이상 상태를 찾는다 | 있다 (대사) |
| **복구** | 상태를 실제로 되돌린다 | **없다. 손으로 한다** |
| **검증** | 복구 후 정말 정상인지 다시 본다 | **없다** |

## 확인해 보니 서술이 낡아 있었다

코드를 읽으니 **main 에 복구(재처리)가 이미 있었다.** `notification` 모듈의 `DeadLetterAdminController` 가 `GET /api/v1/admin/dead-letters`(조회)와 `POST /api/v1/admin/dead-letters/{id}/reprocess`(재발행)를 이미 제공한다. 로드맵의 "복구가 없다"는 **MSA 브랜치의 Kafka DLT(`payment.confirmed-dlt`)를 가리킨 서술**이고, main 의 앱 레벨 DLQ 에는 해당하지 않는다.

**A·B·C 에서 반복된 패턴이 여기서 또 나왔다 — "데이터가 없어 못 고른다"가 아니라 "코드를 읽으면 답이 이미 있었다".** 다만 읽어 보니 두 가지가 실제로 비어 있었다.

## 실제로 비어 있던 두 자리

### 1. 복구가 멱등하지 않았다 (이중 발송 구멍)

`NotificationAdminService.reprocess` 는 DLQ 항목을 재발송하고 성공하면 완료 마킹 후 삭제한다. **그런데 "이미 처리된 이벤트인가"를 보지 않았다.** 이 순서에서 알림이 두 번 나간다.

1. 1차 배달이 발송 실패 → DLQ 격리
2. 재배달(at-least-once)이 성공 → 완료 마킹됨, **그런데 DLQ 항목은 남아 있다**
3. 운영자가 남은 DLQ 항목을 재처리 → **같은 알림을 또 보낸다**

**복구는 "안 간 것을 보낸다"이지 "다시 보낸다"가 아니다.** 재처리가 `processedEvents.existsByEventKeyAndConsumer(...)` 를 먼저 보게 고쳤다 — 이미 처리됐으면 재발송 없이 DLQ 항목만 정리한다.

### 2. 복구 후 검증 표면이 없었다

재처리를 돌린 뒤 **정말 정상이 됐는지 다시 볼 자리**가 없었다. `GET /api/v1/admin/dead-letters/summary` 를 추가해 **남은 격리 건수와 최장 대기 시각**을 낸다. 재처리 전후로 이 값을 비교하면 "복구가 됐는가"가 숫자로 남는다 — 네 번째 책임이 이것이다.

건수만으로는 적체를 못 본다. 방금 쌓인 열 건과 이틀 묵은 한 건은 위험이 다르다. 그래서 `FraudReviewRepository.findOldestCreatedAt` 과 같은 방식으로 최장 대기 시각을 함께 낸다(`DeadLetterRepository.findOldestCreatedAt`).

## 성공 기준

> poison 을 주입해 DLQ 로 격리하고(`NotificationService` 가 실패를 삼켜 적재), 요약으로 그것을 발견하고(`summary` 의 건수·최장 대기가 오른다), 재처리로 복구하고(`reprocess` 가 완료 마킹), 다시 요약해 격리 건수가 0 이 되는 것까지 확인한다. **이미 처리된 건을 재처리해도 알림은 한 번만 나간다**(멱등 가드, `NotificationAdminServiceTest.reprocessAlreadyProcessedDoesNotResend`).

## 정직한 한계

- **main 의 격리 경로는 notification DLQ 하나다.** ADR-024 가 고른 DLT(`payment.confirmed-dlt`, `payment.canceled-dlt`)는 `msa-extraction` 브랜치에 있고 main 에는 그 토픽을 소비하는 코드가 없다. **그 경로의 재처리·검증은 브랜치 소재다** — 이 문서는 main 의 앱 레벨 DLQ 를 다룬다.
- **대사가 DLQ 를 "불일치"로 직접 잡지는 않는다.** 대사는 돈(원장·PG 기록)의 불일치를 본다. 알림 누락은 돈의 불일치가 아니므로 발견 경로가 다르다 — 알림은 `summary` 로, 돈은 대사로 본다. 로드맵이 그린 "poison→DLT→대사→재처리"가 한 줄로 이어지는 것은 **정산을 분리해 Kafka DLT 를 소비하는 브랜치에서** 성립한다.
- **재처리 감사 로그는 있다**(컨트롤러가 `AUDIT` 로거에 호출자·결과를 남김) **하지만 maker-checker 는 없다.** 강제취소처럼 요청자·승인자를 나누지 않았다.

## 다시 볼 조건

- Kafka DLT(`payment.confirmed-dlt`)를 소비하는 서비스가 생기면(브랜치 승격 등) 그 경로에도 재처리 어드민과 복구 후 대사 재실행을 붙인다. 이 문서의 네 책임 틀이 그대로 적용된다
- `summary` 의 `pendingCount` 가 0 이 아닌 채로 오래 머물면 복구가 사람 속도를 못 따라간 것이다. 그때 자동 재처리 배치를 검토한다(지금은 만들지 않는다 — 자동 재처리는 재시도 폭주 위험이 있다)
