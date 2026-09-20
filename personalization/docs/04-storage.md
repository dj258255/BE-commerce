# 04. 저장과 적재

> **M0에서 빠져 있던 결정이다.** M0는 "무엇을 측정할지"는 정했지만 "데이터를 어디에 어떻게
> 넣을지"를 정하지 않았다. 이 문서가 그 자리를 채운다. 결정과 함께 **버린 대안**도 적는다.

## 1. 원칙

- 커머스 코어 DB와 개인화 저장소를 **분리**한다(`01-architecture.md` §5).
- **큰 상호작용 로그(31.8M)를 커머스 DB에 넣지 않는다.** 코어 DB를 오염시키면 경계도 서사도 무너진다.
- 상품은 예외다 — **화면에 팔리려면 커머스 카탈로그에 있어야 한다.**

## 2. 무엇을 어디에

| 데이터 | 위치 | 왜 |
|---|---|---|
| H&M `articles` 105,542 | **커머스 `products`로 승격** | 스토어프론트가 실제로 팔아야 개인화가 화면에 붙는다. 105K행은 MySQL에 부담 없음 |
| H&M `images` 105,542장 | 정적 파일 + `products.image_url` | ~3~6GB. 리포에 넣지 않는다 |
| H&M `customers` 1.37M | 개인화 전용 테이블 + **매핑** | 커머스 회원과 개념이 다르다 |
| H&M `transactions` 31.8M | **Parquet(offline) + Redis(online)** | 커머스 DB에 넣지 않는다 |
| Amazon_Fashion | Parquet(offline) | 조인 불가, 텍스트 보조 |

## 3. 커머스 승격 매핑 (`articles` → `products`)

커머스 `products`는 `product_id BIGINT PK, name, price, category_code, description, image_url, brand, featured, created_at`이다.
H&M을 그대로 넣을 수 없어 **변환 규칙**이 필요하다.

| products | articles | 변환 |
|---|---|---|
| `product_id` | `article_id` | **10자리 zero-pad 문자열 → BIGINT**. `'0123456001'` → `123456001`. **이미지 경로 만들 땐 `%010d`로 되돌린다** |
| `name` | `prod_name` | 그대로 |
| `description` | `detail_desc` | 그대로(최대 764자, 컬럼 1000자) |
| `brand` | (없음) | `index_group_name`(Ladieswear/Menswear/…)을 브랜드 자리에 임시 매핑 |
| `category_code` | (없음) | `section_name` 또는 `index_group_name`을 커머스 `categories`로 매핑(§7 열린 결정) |
| `image_url` | `article_id` | `/uploads/hm/%010d.jpg` |
| `price` | `price` | **합성 규칙 필요**(§7). H&M price는 정규화된 상대가라 그대로 쓸 수 없다 |
| `featured` | (없음) | 기본 0. 큐레이션은 별도 |
| `created_at` | (없음) | 적재 시각 또는 `t_dat` 최초 등장 시각 |

**재고(`stock`)**: H&M에는 재고가 없다. 합성하거나(제약 실험용) 무한 재고로 둔다 — 실험에서 밝힌다.

## 4. 유저 매핑

H&M `customer_id`(1.37M) ↔ 커머스 `userId`(데모 `1`/`2` + `members`).

- **1.37M 고객을 커머스 회원으로 만들지 않는다.** 데모에 과하고, 커머스 회원 도메인과 개념이 다르다.
- `personalization_user_map(user_id, hm_customer_id)` 매핑 테이블을 둔다. 데모 유저는 샘플 고객에 매핑.
- **매핑이 없으면** 인기 폴백으로 응답한다 — 콜드스타트와 같은 경로를 탄다(별도 분기 금지).

## 5. 파이프라인

```text
원본 CSV/ZIP
 1) 정규화   article_id 문자열 유지 · -1/Unknown → 결측 · price를 '상대가'로 표시
 2) Parquet  offline 원본(파티션: t_dat 월별)            ← 31.8M을 여기서 다룬다
 3a) 승격    articles → 커머스 products (+ 카테고리 매핑)
 3b) 피처    user/item 통계, point-in-time 정확 피처      ← 누출 회귀 테스트가 지킨다
 4) 온라인   Redis 컨텍스트 적재
```

- **각 단계는 멱등**이다. 재실행이 같은 결과를 내야 한다(부분 실패 후 재개 가능).
- 실행 로그를 남긴다(무엇을 몇 건 처리했고 무엇을 건너뛰었는지).

## 6. 이미지

- 파일명 = `article_id`(10자리). 커머스 `image_url`은 `%010d`로 경로를 만든다.
- 로컬은 정적 서빙, 운영은 오브젝트 스토리지 → **범위 밖**으로 남긴다.
- **리포에 넣지 않는다**(수 GB). 다운로드 스크립트만 제공한다.
- 서빙은 **리포 밖 디렉터리**를 Spring이 `/uploads/**`로 정적 매핑한다(`UploadsConfig`, `app.uploads.dir`).
  클래스패스(`static/`)에 두면 빌드 산출물이 무거워지고 실수로 커밋되기 쉽다.
- Next.js는 `/uploads/*`를 프록시해 **same-origin**을 유지한다. 이미지가 없으면 화면은 그라디언트로 폴백한다
  — 일부만 있어도 목록이 깨지지 않는다.

## 7. 결정 (M1에서 닫음)

| 결정 | 선택 | 근거 |
|---|---|---|
| 카테고리 축 | **H&M `index_group_name` 5종** (여성복·아동복·Divided·남성복·스포츠) | 기존 스토어프론트 필터를 그대로 쓴다. 56개 `section_name`은 너무 잘다 |
| 가격 | 상품별 상대가 중앙값을 **전체 중앙값이 29,900원이 되도록 선형 사상**, 100원 단위, `[1,000, 500,000]` clamp | 결정적이고 되돌릴 수 있다. 규칙을 바꾸면 재실행 |
| 재고 | **합성 1,000** (H&M에 재고 없음) | 재고 행이 없으면 승인 시 조건부 차감이 0행이라 `OUT_OF_STOCK`으로 실패한다 — 화면은 살 수 있는 것처럼 보이는데 실제로는 못 산다 |
| 이미지 | **부분 확보 20,491장(카탈로그의 19.4%)** → `products.image_url` 컬럼 | Kaggle은 인증이 필요해 전체를 못 받는다. HF 캡션 데이터셋 parquet의 `image.path`에 **원본 파일명(= `article_id.jpg`)** 이 남아 있어 매핑된다(실측). 나머지는 NULL이고 화면은 그라디언트로 폴백 |
| 브랜드 | `H&M` | H&M에 브랜드 컬럼이 없다 |

**적재 방식**: Flyway 마이그레이션이 아니라 `promote_products.py --load`다. 이건 **스키마가 아니라 데이터**이고,
10만 행을 마이그레이션에 박으면 모든 부팅·CI가 그 비용을 낸다. 생성물은 `personalization/data/hm/catalog.sql`(27.7MB, gitignore).

**레거시 데모 상품(1~36)은 남긴다** — k6와 통합 테스트가 1~3번을 참조한다. 대신 `featured`에서 빼서
'추천' 자리를 실제 카탈로그가 갖게 한다.

## 8. 버린 대안

- **31.8M 거래를 커머스 DB에 적재**: 코어 DB 오염 + 경계 위반. Parquet로 충분하다.
- **1.37M 고객을 커머스 회원으로 생성**: 데모에 과하고 회원 도메인을 흐린다.
- **상품을 개인화 전용에만 두기**: 스토어프론트에서 팔 수 없어 개인화 결과가 화면에 안 붙는다(M7이 막힌다).
- **이미지를 리포에 커밋**: 3~6GB를 git에 넣는 건 되돌리기 어렵다.
