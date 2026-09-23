# 관측성 구성

Actuator + Micrometer → Prometheus → Grafana.

## 노출 메트릭

- `payment_confirm_total{outcome="success|failed|timeout"}`: 결제 승인 결과별 카운터
  (PaymentService에서 계측). **결제 성공률 SLO**의 소스.
- `payment_unknown_oldest_age_seconds`: 가장 오래된 UNKNOWN(미확정) 결제의 경과 시간(초).
  미확정이 없으면 0. `PaymentSloMetrics` 게이지. **미확정 방치 SLO**의 소스.
- `recon_pending_count`: 사람 확인이 필요한 PENDING(미해결) 대사 건수. `ReconciliationMetrics` 게이지.
- `recon_pending_oldest_age_seconds`: 가장 오래된 PENDING 대사 건의 경과 시간(초). 미해결이 없으면 0.
  `ReconciliationMetrics` 게이지. **대사 적체 SLO**의 소스.
- Spring Boot 기본: `http_server_requests_seconds_bucket`(p95/p99. 히스토그램 버킷을
  `management.metrics.distribution.percentiles-histogram.http.server.requests=true`로 켜야 노출된다.
  안 켜면 `_count/_sum/_max`만 나와 분위수 산출이 불가능하다), `hikaricp_connections_*`(풀 사용률),
  JVM/시스템 메트릭.

## 실행

관측성 스택은 `monitoring` 프로필로만 기동한다(mysql/redis/kafka는 프로필 없이 기본 기동).

```bash
./gradlew -p commerce bootRun                                          # 앱 — 호스트 8080, /actuator/prometheus 노출
docker compose --profile monitoring up -d prometheus grafana
```

- Grafana: `http://localhost:3000` (익명 Viewer, 대시보드 조회 전용. 데이터소스·대시보드는 프로비저닝으로
  자동 등록되므로 익명 사용자가 Admin일 필요가 없다. Admin이면 익명 사용자가 데이터소스를 추가해
  내부 서비스로 SSRF 피벗이 가능해지므로 Viewer로 최소화한다. 포트도 127.0.0.1 전용 바인딩.)
- Prometheus: `http://localhost:9090`
- 알림 룰 상태: `http://localhost:9090/alerts`

prometheus는 `host.docker.internal:8080`을 스크레이프하므로 앱은 호스트에서 bootRun으로 띄운다.
`extra_hosts`로 리눅스에서도 `host.docker.internal`이 호스트를 가리킨다.

### 스크레이프 엔드포인트 개방

수집기는 Bearer 없이 `/actuator/prometheus`를 주기 GET 하므로, 이 엔드포인트만 `SecurityConfig`에서
개방한다(health/info와 함께). 나머지 actuator(env·heapdump·modulith 등)는 계속 ADMIN으로 잠근다.
운영에서는 `management.server.port`를 내부망 전용으로 분리해 스크레이프하는 것이 정석이다.

## Grafana 핵심 패널

1. 결제 성공률(SLO) = success / (success+failed+timeout), stat, 95% 임계
2. 결제 처리량(TPS): `rate(payment_confirm_total[1m])`
3. 미확정(UNKNOWN) 결제 최고 경과(초): `payment_unknown_oldest_age_seconds` (300s 주의/600s 위험)
4. 대사 미해결 건수(PENDING): `recon_pending_count`
5. 결제 승인 결과(결과별 rate): `sum by (outcome) (rate(payment_confirm_total[1m]))`
6. 요청 레이턴시 p95/p99: `histogram_quantile(…, http_server_requests_seconds_bucket)`
7. HikariCP 커넥션 사용(active/max): 풀 포화 신호

## 알림 룰

| 이름 | 조건 | 심각도 |
| --- | --- | --- |
| PaymentSuccessRateLow | 최근 5분 결제 성공률 < 95% (5m 지속) | critical |
| CompensationExhausted | 최근 10분 내 보상 재시도 소진 > 0 | critical |
| UnknownPaymentAging | 미확정 결제 최고 경과 > 600초 (5m 지속) | critical |
| DeadlockRetrySpike | 멱등키 데드락 재시도 > 10회/분 (5m 지속) | warning |
| ReconPendingBacklog | 대사 PENDING 최고 경과 > 900초 (5m 지속) | warning |

> 성공률 알림은 트래픽이 없으면 분모가 0(0/0=NaN)이라 발화하지 않는다. 유휴 시 오탐이 없다.

## 알림이 실제로 가는 곳

규칙만 있고 받는 데가 없으면 아무도 모른다. `UnknownPaymentAging`이 울려도
그래프에 빨간 줄이 그어질 뿐이다. **관측은 누가 보느냐까지가 설계다.**

`alertmanager.yml`이 그 자리를 채운다.

```
export SLACK_WEBHOOK_URL='https://hooks.slack.com/services/...'
alertmanager --config.file=monitoring/alertmanager.yml
```

두 가지를 나눴다.

- **채널을 둘로 가른다.** `severity=critical`은 별도 채널로 간다. 같은 채널에 섞으면
  "옛 해시가 남아 있다"와 "미확정 결제가 10분째"가 같은 무게로 보인다
- **재알림을 30분(critical 은 10분)에 한 번으로 묶는다.** 미확정이 쌓이는 상황은 몇 초마다
  재알림해도 대응이 빨라지지 않고, 채널이 시끄러우면 사람이 알림 자체를 끈다

웹훅 URL 은 코드에 두지 않고 환경변수로 넣는다.
