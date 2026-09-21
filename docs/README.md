# 문서 안내

BE-commerce는 이커머스 플랫폼이고, 그 코어는 결제의 정합성입니다. 이 디렉터리는 **커머스·결제 도메인 설계**,
재현 실험, 아키텍처 결정 기록을 분리해 보관합니다.
전체를 순서대로 읽기보다 아래 경로를 따라가면 프로젝트의 문제 정의와 검증 근거를 빠르게 확인할 수 있습니다.

## 처음 읽는 순서

| 순서 | 문서 | 확인할 내용 |
| ---: | --- | --- |
| 1 | [프로젝트 README](../README.md) | 구현 범위, 핵심 결과, 실행 방법 |
| 2 | [결제 도메인 핵심 개념](02-결제도메인-핵심개념.md) | 승인·취소·정산·대사의 기본 용어 |
| 3 | [아키텍처 설계](03-아키텍처-설계.md) | 멱등성, 사가, Outbox, 원장, 웹훅 |
| 4 | [장애 시나리오](04-장애-시나리오-설계.md) | PG 실패를 상태로 보존하고 복구하는 방법 |
| 5 | [성능 리포트](performance/README.md) | 측정 방법, 병목, 잘못된 가설을 교정한 기록 |
| 6 | [ERD](09-ERD-설계.md) · [API 스펙](10-API-스펙.md) | 데이터 불변식과 외부 계약 |

## 핵심 설계 문서

| 문서 | 역할 |
| --- | --- |
| [02. 결제 도메인 핵심 개념](02-결제도메인-핵심개념.md) | 가맹점·PG·카드사 구조와 결제 상태머신 |
| [03. 아키텍처 설계](03-아키텍처-설계.md) | 결제 시스템의 주요 신뢰성 패턴 |
| [04. 장애 시나리오 설계](04-장애-시나리오-설계.md) | 타임아웃·중복·순서 역전·부분 실패 대응 |
| [05. 성능 개선 전략](05-성능개선-전략.md) | 목표 설정부터 회귀 게이트까지의 측정 원칙 |
| [09. ERD 설계](09-ERD-설계.md) | 테이블, 키, 유니크 제약과 설계 근거 |
| [10. API 스펙](10-API-스펙.md) | 엔드포인트, 멱등키, 권한, 에러 시맨틱 |
| [28. 스토어프론트 설계](28-스토어프론트-설계.md) | 이커머스 화면 구성과 결제 흐름 |

## 성능과 복원력

| 문서 | 핵심 결과 |
| --- | --- |
| [성능 리포트](performance/README.md) | 부하 모델 교정, 유입 제어, 암호화 비용, Outbox·쿼리 병목 |
| [모놀리스 한계 실측](performance/msa-baseline-experiments.md) | 정산 스케줄러 중복과 배포 결합을 재현하고 분리 근거를 검증 |
| [포인트 적립 경합](performance/point-accrual-contention.md) | 결제 트랜잭션 안의 포인트 경합과 대안 비교 |
| [FDS 지연 예산](17-FDS-지연예산-실측.md) | 탐지기를 승인 경로에 넣을 수 있는지 p99 기준으로 검토 |
| [배치 조회 상한](23-배치-조회-상한.md) | 적체 데이터를 무제한으로 읽던 배치에 처리 상한 적용 |
| [조회 인덱스 실측](24-조회-인덱스-실측.md) | 반환 행 수와 스캔 행 수를 분리해 인덱스 효과 검증 |
| [알림 소음과 표본 하한](25-알림-소음-억제와-표본바닥.md) | 비율 알림의 작은 분모와 반복 알림 제어 |

## 운영 자동화와 평가

아래 문서는 결과만 정리한 명세가 아니라, 가설이 틀렸을 때 기준과 구현을 고친 과정까지 남긴
실험 기록입니다. 현재 동작과 최종 결정은 각 문서의 요약·결론을 우선해 읽어야 합니다.

| 문서 | 현재 결론 |
| --- | --- |
| [11. AI 운영 자동화 검토](11-AI-운영자동화-검토.md) | 결정 가능한 영역은 규칙으로 처리하고 모델은 후보·초안에 한정 |
| [12. 사례 연구](12-AI-운영자동화-사례연구.md) | 국내외 사례와 출처 신뢰도 정리 |
| [13. 상담 답변 초안 실측](13-상담초안-실측.md) | 숫자·용어 가드와 블라인드 쌍 비교; 자동 발송은 제외 |
| [14. 잔여 원인 후보 설계](14-백오피스-AI-잔여후보-사례조사.md) | 규칙이 못 가른 영역에 모델을 검토한 설계·실험 기록 |
| [15. 잔여 후보 홀드아웃](15-잔여후보-홀드아웃-실측.md) | 규칙 대비 개선이 없어 모델 노출을 중단 |
| [16. 할부 도메인](16-할부-도메인.md) | FDS 규칙을 추가하기 전에 할부의 책임과 데이터 경계 확인 |
| [18. 운영자용 타임라인 서술](18-운영자용-타임라인-서술.md) | 사실 가드와 템플릿 폴백을 둔 운영자용 요약 |
| [19. 장애 로그 원인 분석](19-장애-로그-원인분석.md) | 규칙 우선 분석과 근거 인용, 평가 기준선 회귀 감시 |
| [20. 분쟁 증빙 조립](20-분쟁-증빙-조립.md) | 증빙 항목은 규칙으로 조립하고 제출은 사람이 수행 |
| [21. 자동확정 승격 기구](21-자동확정-승격-기구.md) | 자동확정 조건을 코드와 측정값으로 표현하되 기능은 비활성 |
| [22. 제재 스크리닝](22-제재-스크리닝-이름매칭.md) | 판매자 지급 전 이름 매칭의 데이터·정규화 한계 |
| [26. FDS 규칙별 오탐](26-FDS-규칙별-오탐.md) | 전체 평균이 숨기던 규칙별 오탐을 분리 측정 |
| [27. FDS 모델 평가](27-FDS-모델-평가와-켤-조건.md) | 모델은 이진 판정이 아니라 심사 큐 정렬에만 사용 |

## 아키텍처 결정 기록

ADR은 당시의 맥락, 선택, 대안, 대가를 보존합니다. 이후 구현으로 결정이 바뀐 경우 문서 상단에
개정 또는 대체 상태를 표시합니다.

| ADR | 결정 |
| --- | --- |
| [ADR-001](adr/ADR-001-architecture-spring-modulith.md) | Spring Modulith 기반 모듈형 모놀리스 채택 |
| [ADR-002](adr/ADR-002-outbox-event-publication-registry.md) | Event Publication Registry로 Transactional Outbox 구현 |
| [ADR-003](adr/ADR-003-stock-deduction-timing.md) | 승인 성공 시점에 재고 차감 |
| [ADR-004](adr/ADR-004-stock-deduction-locking.md) | 조건부 `UPDATE` 기반 재고 차감 |
| [ADR-005](adr/ADR-005-event-externalization-kafka.md) | 결제 이벤트를 Kafka로 선택적 외부화 |
| [ADR-006](adr/ADR-006-envelope-encryption-dek-kek.md) | DEK/KEK 기반 필드 암호화 |
| [ADR-007](adr/ADR-007-checkout-transaction-boundary.md) | 외부 호출을 분리한 3단계 체크아웃 사가로 개정 |
| [ADR-008](adr/ADR-008-recon-mismatch-analysis-assistant.md) | 대사 사유 기록 제안; 분석 방식은 ADR-012가 대체 |
| [ADR-009](adr/ADR-009-password-hashing-and-migration-path.md) | Argon2id와 로그인 시 점진 이관 |
| [ADR-010](adr/ADR-010-what-not-to-build.md) | 구현 범위를 결정하는 기준 |
| [ADR-011](adr/ADR-011-order-timeline-assembly.md) | 주문 전 과정 타임라인 조립 |
| [ADR-012](adr/ADR-012-rule-based-cause-classifier.md) | 규칙 기반 대사 원인 후보와 사람 확정 |
| [ADR-013](adr/ADR-013-cancellation-as-separate-recon-row.md) | 취소를 대사에 별도 행으로 기록 |
| [ADR-014](adr/ADR-014-cs-draft-port-and-number-guard.md) | 상담 초안 포트와 숫자 출처 가드 |
| [ADR-015](adr/ADR-015-subscription-billing-anchor.md) | 앵커 기준 다음 청구일 계산 |
| [ADR-016](adr/ADR-016-money-with-currency.md) | 통화를 포함한 금액 값 타입 |
| [ADR-017](adr/ADR-017-aggregate-boundaries.md) | 주문·상품·재고 애그리거트 분리 |
| [ADR-018](adr/ADR-018-module-internal-packages.md) | 모듈 공개 API와 내부 패키지 분리 |
| [ADR-019](adr/ADR-019-pci-scope-by-not-touching-cards.md) | 카드번호를 직접 처리하지 않아 PCI 범위 축소 |
| [ADR-020](adr/ADR-020-multi-pg-routing-off-by-default.md) | 멀티 PG 라우팅은 계약 전까지 기본 비활성 |
| [ADR-021](adr/ADR-021-no-aml-screening-by-not-holding-identity.md) | 고객 AML과 판매자 제재 스크리닝의 범위 분리 |
| [ADR-022](adr/ADR-022-pg-brownout-resource-limits.md) | 느린 PG 앞의 자원 고갈 — 동시 호출 상한 |
| [ADR-023](adr/ADR-023-settlement-earned-date-vs-swept-date.md) | 정산 집계일과 거래 귀속일 분리 여부 |
| [ADR-024](adr/ADR-024-settlement-extraction-data-cutover.md) | 정산 분리 시 과거 데이터 이관 |
| [ADR-025](adr/ADR-025-ledger-balance-read-strategy.md) | 원장 잔액 읽기 전략(SUM 대 스냅샷) |
| [ADR-026](adr/ADR-026-event-schema-evolution.md) | 이벤트 스키마 진화와 배포 순서 |
| [ADR-027](adr/ADR-027-settlement-fairness-policy.md) | 정산 판매자 공정성 정책 |
| [ADR-028](adr/ADR-028-fds-threshold-with-delayed-labels.md) | FDS 임계값과 지연 라벨 |
| [ADR-029](adr/ADR-029-deployment-unit-vs-service-boundary.md) | 실행 단위만 분리하는 것과 저장소까지 분리하는 것 |
| [ADR-030](adr/ADR-030-dlt-replay-and-post-recovery-reconciliation.md) | 격리·발견·복구·검증의 분리와 DLT 재처리 |
| [ADR-031](adr/ADR-031-storefront-catalog-read-api.md) | 쇼핑몰 카탈로그를 읽기 전용으로 연다 |
| [ADR-032](adr/ADR-032-frontend-nextjs-adoption.md) | 프론트 스택을 Next.js로 전환한다 |
| [ADR-033](adr/ADR-033-wishlist-server-side.md) | 찜을 서버에 저장하고 로그인을 요구한다 |
| [ADR-034](adr/ADR-034-personalization-context-deployment-unit.md) | 개인화 온라인 컨텍스트를 같은 배포 단위에 둔다 |
| [ADR-035](adr/ADR-035-order-tolerant-context-merge.md) | 컨텍스트 병합을 순서에 관대하게 만든다 |
| [ADR-036](adr/ADR-036-context-window-aggregate.md) | 창 집계를 목록에서 떼어낸다 — 세는 일에는 세는 그릇을 준다 |
| [ADR-037](adr/ADR-037-overload-admission-policy.md) | 과부하에서 무엇을 포기하는가 — 정책은 지연을 사고, coverage는 모델이 정한다 |
| [ADR-038](adr/ADR-038-constraint-revalidation-timing.md) | 제약을 언제 다시 확인하는가 — 추천은 stale을 허용하고 구매는 강보장한다 |
| [ADR-039](adr/ADR-039-constraint-source-real-stock.md) | 제약의 원천을 실제 재고로 잇는다 — 추천이 커머스 재고를 읽는 경계 |
| [ADR-040](adr/ADR-040-generation-scope-and-budget.md) | 홈 페이지를 어떻게 만드는가 — 생성 범위는 비용을 사고, 품질은 재지 않았다 |
| [ADR-041](adr/ADR-041-cache-compression-threshold.md) | 캐시 압축은 1KB부터 — 근거는 생겼고 적용할 자리는 아직 없다 |
| [ADR-042](adr/ADR-042-home-composer-boundary.md) | 홈 컴포저를 별도 경계로 둔다 — 페이지는 추천의 합이 아니다 |
| [ADR-043](adr/ADR-043-home-impression-log.md) | 홈 노출은 요청당 한 행으로 남긴다 — 관측을 도메인 이벤트에 태우지 않는다 |

## 보조 문서

- [관측성 구성](../monitoring/README.md): Prometheus·Grafana·Alertmanager 실행과 지표 정의
- [Kafka 소비자 데모](../consumer-app/README.md): 프로세스 밖 이벤트 소비 예제
- [FDS 학습기 대조](../tools/fds/README.md): Java 학습기와 scikit-learn 기준자 비교

## 문서 원칙

- 구현된 사실, 측정 결과, 향후 계획을 구분합니다.
- 성능 수치에는 환경·부하 모델·표본 크기를 함께 적습니다.
- 결정이 바뀌면 이전 기록을 지우지 않고 상단 상태와 후속 ADR로 연결합니다.
- 외부 사례는 설계의 참고 근거이며, 이 저장소에서 재현한 결과와 구분합니다.
