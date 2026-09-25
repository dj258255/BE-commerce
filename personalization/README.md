# personalization

BE-commerce의 개인화 시스템. **모델을 잘 만드는 프로젝트가 아니다.**

## 무엇을 하는가

AI 모델이 실제 request path 안에 들어왔을 때 **백엔드가 무엇을 보장해야 하는가**를 측정한다.

| 질문 | 무엇을 보는가 |
|---|---|
| 신선도 vs 지연 | 최신 이벤트를 모델에 넣으려면 얼마나 기다려야 하고, 그 대가는? |
| online/offline 일치 | 같은 로그로 만든 학습·서빙 피처가 어디서 갈라지는가 |
| 과부하 | 모델이 포화됐을 때 SLO를 지키려면 모델 사용을 얼마나 포기하는가 |
| 제약 | 확률적 출력을 business state로 쓸 때 어디까지 재검증하는가 |
| 계산 예산 | 생성 범위를 늘리면 모델이 latency budget에서 차지하는 몫은 |
| 캐시 | 값이 몇 KB를 넘을 때부터 압축이 이득인가 |

**성공 기준은 "추천 품질이 좋아졌다"가 아니다.** 서로 충돌하는 요구사항에서 하나를 더 보장할 때
다른 쪽에서 실제로 얼마를 지불하는지를 숫자로 남기는 것이다. 실험에서 재현되지 않으면 그것도 결과다.

## 현재 상태

- **백엔드**: 앱 안의 `recommendation`·`home`·`personalization`·`experiment` 모듈로 돌아간다. 실험 E1~E6 과 홈 조립(M7)을 실측으로 닫았고, 마일스톤 M0~M12 는 완료다([ROADMAP.md](ROADMAP.md))
- **모델**: ALS 는 기준선을 못 넘어 넣지 않았다([ADR-048](../docs/adr/ADR-048-als-model-not-adopted.md)). GenPage 소형 모델은 서빙 경로까지 붙였지만 기본값은 꺼져 있다([ADR-053](../docs/adr/ADR-053-genpage-mini-not-default.md) · [ADR-060](../docs/adr/ADR-060-genpage-serves-purchases.md)). 켜는 판정은 A/B 이고 그 기반은 합성 사용자로만 검증했다([ADR-061](../docs/adr/ADR-061-ab-assignment-and-attribution.md))
- **진행 중**: GenPage v2(`genpage2/`, [docs/genpage-v2](docs/genpage-v2/)). 원문 구성 요소를 데이터가 허락하는 데까지 옮기고 두 발견을 다시 잰다
- **프론트**: 개인화 홈과 실험 콘솔은 `apps/web`(Next.js)으로 옮겼다. `web/` 는 이전 정적 화면이다
- **M12 이후의 결정**(모델 서빙·과부하 재측정·홈 다음 쪽 입력 등)은 ROADMAP 표에 아직 행이 없다. [ADR](../docs/adr/) 053 이후와 [트러블슈팅 기록](../docs/TROUBLESHOOTING-LOG.md)이 그 기록이다

## 문서

| 문서 | 무엇 |
|---|---|
| [ROADMAP.md](ROADMAP.md) | 지금 어디까지 왔는가 (계획/구현/검증/릴리스 구분) |
| [docs/00-data.md](docs/00-data.md) | 데이터 계약과 품질 — 조인 키, 함정, 합성 데이터 구분 |
| [docs/01-architecture.md](docs/01-architecture.md) | offline/nearline/online 평면, 컴포넌트, 경계, 분리 조건 |
| [docs/02-experiments.md](docs/02-experiments.md) | 실험 7종의 가설·지표·방법·판정 |
| [docs/03-verification.md](docs/03-verification.md) | 검증 기록 형식과 규칙 |
| [docs/04-storage.md](docs/04-storage.md) | **저장과 적재** — 상품 승격 매핑, 유저 매핑, 파이프라인 |
| [web/README.md](web/README.md) | 프론트 실행 방법과 목 계약 |

## 경계

`BE-commerce` 모노레포 안의 자기완결 영역이다. 지금은 통째로 떼어낼 수 있는 형태로만 유지하고,
서비스 분리는 경계가 측정으로 증명된 뒤에 결정한다.

```
personalization/
├── pipeline/   데이터 수집·정규화·피처·학습(ALS, GenPage v1)
├── serving/    GenPage 모델 서버(파이썬 HTTP)
├── genpage2/   GenPage v2(진행 중)
├── docs/       데이터 계약·아키텍처·실험 명세·검증 기록(runs/)
└── web/        이전 정적 화면. 지금 화면은 apps/web
```

## 데이터

- **H&M**(거래 31.8M·상품 105,542·이미지)이 주 코퍼스. 이미지 제외 린 슬라이스 ~1.1GB
- **Amazon_Fashion**(5-core)은 텍스트·콜드스타트 **보조**. 두 데이터셋은 공통 ID가 없어 조인하지 않는다
- 공개 데이터에 없는 impression·세션·재고는 **synthetic으로 만들어 명확히 구분**한다
