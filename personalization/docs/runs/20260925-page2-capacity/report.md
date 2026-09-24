# 홈 2쪽이 요청마다 돌던 count 쿼리 (#284)

홈 2쪽 앱 경로의 용량이 이 머신에서 **초당 30건에서 120건으로 올랐다**(k6 dropped 0 기준). 2쪽 요청 한 번이 결과에 쓰지 않는 count 쿼리를 최대 10개 돌리고 있었다. 판정 기준은 [이슈 #284](https://github.com/dj258255/BE-commerce/issues/284)에 **측정 전에** 적었다.

## 무엇이 돌고 있었나

| 호출 | 무엇을 돌았나 | 단일 실행 중앙값 |
|---|---|---:|
| `ProductCatalogFacts.topCategoryNames()` | 대분류 이름 5개가 필요한데 `categories()` 를 불러 대분류마다 상품 수를 셌다 | count 5개 합 19.2ms |
| `ProductCatalogFacts.newestInCategory()` | 목록 검색(`search`, `Page`)을 재사용해 신상품 24개와 함께 대분류 전체 개수를 셌다. 인기 표에 모자란 대분류마다 | count 1.0 ~ 7.1ms (신상품 select 는 0.7 ~ 2.2ms) |

신상품 select 는 `idx_products_created` 를 거꾸로 훑어 1ms 안팎이다. 시간을 쓴 것은 결과에 쓰지 않는 개수 세기다. `topCategoryNames` 는 GenPage 행 경로에서도 돈다.

## 바꾼 것

- `topCategoryNames` 는 카테고리 표(대분류 5행)에서 이름만 읽는다(`CatalogQueryService.topCategoryNames`)
- `newestInCategory` 는 목록을 돌려주는 조회(`ProductRepository.findIdsByCategoryCode`)로 바꿨다. `List` 반환이라 count 가 돌지 않는다. 정렬(created_at · product_id 내림차순)은 그대로다

## 결과

모델 없이(규칙 행), 추천 행 1/s 를 깔고 2쪽 부하를 올렸다(`tools/run-page2-capacity.sh`, k6 열린 루프 40초, 워밍업 10초 제외). 고치기 전(main)과 고친 뒤를 같은 시간대에 차례로 쟀다.

| 2쪽 부하 | 고치기 전 p95 | 달성 | dropped | 고친 뒤 p95 | 달성 | dropped |
|---:|---:|---:|---:|---:|---:|---:|
| 30/s | 166.3ms | 27 | 0 | **39.0ms** | 28 | 0 |
| 60/s | 4,437.1ms | 34 | 840 | 37.6ms (첫 실행 626.4ms) | 53 | 0 |
| 90/s | 4,985.7ms | 35 | 2,016 | 56.6ms | 72 | 0 |
| 120/s | 4,405.4ms | 35 | 3,241 | 43.2ms | 106 | 0 |
| 150/s | 4,313.1ms | 34 | 4,444 | 129.7ms | 131 | 9 |

(달성은 k6 의 http_reqs 비율이라 setup 시간이 섞여 부하보다 낮게 나온다. 판정은 dropped 다.)

- **채택.** dropped 0 인 가장 큰 부하가 30/s → 120/s(기준: 60/s 이상). 30/s 의 p95 는 166.3 → 39.0ms(기준: 나빠지지 않을 것)
- 고치기 전은 부하와 상관없이 달성이 초당 약 35건에서 멈췄다. 앞선 측정(#271)의 P = 30/s 가 그대로 재현됐다
- 고친 뒤 60/s 첫 실행의 p95 626.4ms 는 다시 재니 37.6ms 였다. 첫 값은 한 번의 멈춤으로 보고 둘 다 적는다

## 추가: ADR-067 의 다시 볼 조건 — 실제 모델로 자리 없음 대 자리 공유

앱이 2쪽을 120/s 까지 받게 되어, 2쪽 호출이 모델 서버를 포화시키는 부하(모델 직접 `/page` 121/s)를 앱을 거쳐 만들 수 있게 됐다. 기준은 이슈 #284 댓글에 재기 전에 적었다(`tools/run-page2-shared-capacity.sh`, 실제 GenPage, 추천 행 150/s, 대기 추정 OBSERVED, 조건마다 모델 서버를 새로 띄운다).

| 2쪽 부하 | page-capacity | 추천 행 coverage | 추천 행 p95 | 추천 행 모델 실패 | 2쪽 모델 행 | 2쪽 p95 | dropped(추천 · 2쪽) |
|---:|---|---:|---:|---:|---:|---:|---:|
| 90/s | none | 0.0% | 1,631.7ms | 254 | 0.0% | 10,987.0ms | 4,019 · 2,888 |
| 90/s | **shared** | 99.9% | **35.0ms** | **0** | 59.7% | 130.5ms | **0 · 0** |
| 120/s | none | 0.0% | 1,840.0ms | 254 | 0.0% | 12,137.6ms | 3,688 · 3,733 |
| 120/s | **shared** | 99.8% | **35.0ms** | **0** | 41.8% | 122.2ms | **0 · 0** |

- shared 가 기준(추천 행 모델 실패 0, p95 ≤ 126ms)을 지켰다. **기본값 shared 를 유지한다**
- none 은 두 부하 모두 추천 행까지 무너졌다. 이번에는 2쪽 앱 경로가 이 부하를 받으므로(위 결과) 무너짐은 게이트 밖 모델 호출 때문이다
- shared 에서 모델 자리를 못 얻은 2쪽(40 ~ 58%)은 규칙 행으로 갔다. 규칙 행이 이 PR 로 싸져서 앱이 버텼다. 두 변경이 맞물린 결과다
- 첫 시도에서는 none 조건 뒤에 모델 서버가 버려진 요청을 계속 계산해 다음 조건의 앱 기동이 200초를 넘겼다. 조건마다 모델 서버를 새로 띄워 다시 쟀다

## 한계

- 한 머신(Apple M2 Pro)에서 앱 · MySQL(도커) · k6 가 같이 돈다
- 2쪽 부하는 계정 8개가 같은 커서를 반복한다
- 규칙 행과 GenPage 행의 비용 차이(#271: 30/s 에서 164.7 대 91.0ms)는 대부분 이 count 쿼리였을 것으로 보지만, GenPage 행 경로는 다시 재지 않았다

## 재현

```bash
./gradlew -p commerce bootJar
ITEMS=<run-overload-real-model.sh 가 만든 activity-items.json> bash tools/run-page2-capacity.sh
```
