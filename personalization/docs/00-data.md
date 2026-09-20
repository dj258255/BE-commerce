# 00. 데이터 계약과 품질

> 실측 전에 무엇을 쓰고, 무엇을 만들고, **무엇을 조인하지 않는지**를 고정한다. 실험이 끝나면 이
> 문서의 추정은 실측으로 대체된다. 추정과 실측은 문장에서 구분한다.

## 1. 두 코퍼스

| | H&M Personalized Fashion Recommendations | Amazon Reviews 2023 |
|---|---|---|
| 성격 | **주 코퍼스** — 구매 이력 + 상품 + 이미지 | **보조** — 리뷰 텍스트·메타 |
| 상호작용 | 31.8M 구매 (암묵적 양성) | 571.5M 리뷰 (명시적 평점) |
| 사용자 | 1.37M | 54.5M |
| 아이템 | 105,542 | 48.2M |
| 이미지 | 105,542장 (512×512) | URL만 (일부 만료) |
| 기간 | 2020-09 ~ 2021-09 | 1996-05 ~ 2023-09 |

**주 코퍼스인 이유**: 사용자·아이템·이미지·시간순 상호작용이 한 곳에 있고, 패션은 이미지가
의미를 갖는다(콘텐츠 기반 콜드스타트를 실제로 실험할 수 있다).

## 2. H&M 파일과 조인

| 파일 | 필드 | 잇는 키 |
|---|---|---|
| `transactions_train.csv` | `t_dat`, `customer_id`, `article_id`, `price`, `sales_channel_id` | **`customer_id` ↔ `article_id`** |
| `articles.csv` | `article_id`, `product_code`, `prod_name`, `product_type_no/name`, `product_group_name`, `graphical_appearance_no/name`, `colour_group_code/name`, `perceived_colour_value_id/name`, `perceived_colour_master_id/name`, `department_no/name`, `index_code/name`, `index_group_no/name`, `section_no/name`, `garment_group_no/name`, `detail_desc` | `article_id` |
| `customers.csv` | `customer_id`, `FN`, `Active`, `club_member_status`, `fashion_news_frequency`, `age`, `postal_code` | `customer_id` |
| `images/` | jpg 105,542장 | **파일명 = `article_id`** |

**조인은 오직 `transactions`를 통해서만** 된다. `articles`↔`customers` 사이에 FK는 없다.
user-item 행렬을 만들려면 `transactions`가 유일한 근거다.

## 3. H&M 함정 (실측으로 확인할 것)

| 함정 | 왜 문제인가 | 대응 |
|---|---|---|
| **`article_id`가 10자리 zero-padded** | 숫자로 읽으면 앞자리가 소실되고 이미지 파일명과 어긋난다 | **문자열로 다룬다** |
| **`price`가 통화 금액이 아님** | 정규화된 상대가로 알려져 있다. 돈으로 쓰면 틀린다 | 값 분포를 먼저 확인하고, 금액 피처로 쓰지 않는다 |
| **구매만 있음(암묵적 양성)** | 노출/클릭이 없어 네거티브 정의가 임의적이다 | 네거티브 샘플링을 명시하고, 그 선택을 기록한다 |
| **재구매가 많음** | 같은 `article_id` 반복이 성과를 부풀린다 | repeat-aware 지표를 함께 본다 |
| **시간 드리프트** | 랜덤 스플릿은 미래를 새게 한다 | 시간 스플릿(홀드아웃 = train 종료 다음 주) |
| **`product_code` = variant 묶음** | 사이즈·색이 다른 article이 같은 product | 집계 단위를 명시(article 기준) |
| **결측이 `-1`·`Unknown` 센티널** | NULL이 아니라 값처럼 들어온다 | 로드 시 센티널을 결측으로 변환 |
| `age` 결측, `postal_code` 마스킹 | 인구통계 피처가 반쪽 | 피처로 쓸 때 결측률을 함께 기록 |

## 4. Amazon은 어디에 쓰나

`parent_asin`(리뷰) → `parent_asin`(메타)로 아이템을 찾는다. **`asin`이 아니다** — 공식 문서가
"메타는 parent ID로 찾으라"고 명시한다. 일부 아이템은 메타가 아예 없다.

| 함정 | 대응 |
|---|---|
| `price`는 **수집 시점** 가격(USD) | 시계열 아님. H&M 정규화 가격과 비교 불가 |
| 카테고리 문자열 계층이 파편화 | 문자열 그대로 신뢰하지 않는다 |
| `details` 키가 제각각 | 정규화하지 않고 원문 유지 |
| 리뷰 다국어·스팸, `helpful_vote` 편향 | 필터 없이 품질 판단에 쓰지 않는다 |
| 전체는 수백 GB | **`Amazon_Fashion`(5-core)만** 쓴다 |

**쓰임**: H&M 신규 article(거래 없음)의 콜드스타트에 텍스트 신호를 얹는 용도. 두 코퍼스를
하나의 카탈로그로 합치지 않는다 — 공통 ID가 없고, 억지로 합치면 서사만 흐려진다.

## 5. 합성 데이터 (실데이터와 섞지 않는다)

공개 데이터에 없는 것들은 **만들되 명확히 표시**한다. 이게 없으면 실험 자체가 불가능하다.

| 합성 대상 | 왜 필요한가 | 표시 |
|---|---|---|
| impression(노출) | 구매만으로는 반사실 평가가 불가 | `_synthetic: true` |
| session | 홈 요청의 컨텍스트 | `_synthetic: true` |
| 재고·판매상태 | 제약 검증 실험(M4) | `_synthetic: true` |

## 6. 규모·디스크 예산 (실측/추정 구분)

| 항목 | 크기 | 근거 |
|---|---|---|
| H&M 표 데이터 3종 | ~1.08 GB (parquet) | 실측(HF 미러) |
| H&M 이미지 105,542장 | ~3~6 GB | **추정**(장당 30~60KB) |
| Amazon_Fashion 리뷰+메타 | 0.48 GB (gz) | 실측(HEAD) |
| 파생물(피처·인덱스·실험 로그) | 원본의 2~5배 | 추정 |

**린 슬라이스(이미지 제외)는 5GB 이내**에서 시작한다. 이미지는 콘텐츠 기반 콜드스타트를 할 때 붙인다.
