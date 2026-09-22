# H&M 상품 이미지 확보 리포트

> 이미지 확보 경로와 커버리지 실측.
> **현재: 카탈로그의 99.6%에 이미지가 있다**(2026-09-22, Kaggle 경로 확보).
> 그전까지는 19.4%였다. 그 경위를 아래에 남긴다 — 이전 기록을 지우지 않는다.

## 최종 실측 (2026-09-22)

| 항목 | 값 |
|---|---|
| 이미지 파일 | **105,100장** |
| 내려받은 번들 | `h-and-m-personalized-fashion-recommendations.zip` = **30.81GB** (`30,810,293,747 bytes`) |
| 압축을 푼 것 | 34.56GB / 105,104 파일(이미지 105,100 + csv 3) |
| DB 커버리지 | **105,100 / 105,545 = 99.6%** |
| 미부착 | 445개 — 아래에서 설명한다 |

```
select count(*), sum(image_url is not null) from products;
-- 105545, 105100
```

**미부착 445개는 데이터의 한계다. 더 받을 것이 없다:**

| 사유 | 수 |
|---|---:|
| 데이터셋에 이미지가 없는 article | 442 |
| 레거시 테스트 상품(id 1·2·3) | 3 |

> ⚠ **"105,542장"은 틀린 값이었다.** 그건 `articles.csv` 의 **상품(article) 수**이고 이미지 수가 아니다.
> 실제 이미지는 **105,100장**이다. 이 문서와 이슈 #173 이 그 수를 잘못 인용하고 있었다.

## Kaggle 경로 (2026-09-22 확보)

인증은 **access token(`Bearer`)** 이다. 예전 `kaggle.json`(username+key → Basic)이 아니다 —
같은 토큰으로 Basic 을 쓰면 401 이다. 토큰은 `~/.kaggle/access_token`(600)에 둔다.

**손으로 짠 수집기(`fetch_hm_images_kaggle.py`, PR #165)는 쓰지 않는다.**
그 코드는 실제 API 와 세 군데 어긋나 있었고 **한 번도 실제로 돌지 않았다**:

| 코드의 가정 | 실제 |
|---|---|
| `kaggle.json` 의 username+key → Basic | `access_token` + Bearer |
| 목록이 plain list | `{"files":[…],"nextPageToken":…}` 객체 |
| `.zip` 만 받는다 | 목록은 개별 jpg 10만여 개 + csv 2개 |

대신 **공식 CLI** 를 쓴다(`personalization/pipeline/.venv`, gitignore 대상):

```bash
KG=personalization/pipeline/.venv/bin/kaggle
$KG competitions list --group entered            # 먼저 참가 여부(userHasEntered) 확인
$KG competitions download -c h-and-m-personalized-fashion-recommendations -p personalization/data/hm/raw
unzip -q -n personalization/data/hm/raw/*.zip "images/*" -d personalization/data/hm/raw
#   images/010/xxxx.jpg → personalization/data/hm/images/ 로 평탄화(attach_images.py 는 평탄한 *.jpg 를 본다)
python3 personalization/pipeline/attach_images.py --load
```

- **전제**: Kaggle 대회 **Rules 를 수락**해야 한다. 안 하면 다운로드가 403 이다.
  **목록 조회는 200 이라 헷갈린다** — 참가한 다른 대회와 결과를 비교하면 확실하다
- 중간에 끊겨도 다시 실행하면 이어받는다(HTTP Range + `.kaggle-partial` 검증 마커)
- 무결성: 추출 결과가 zip 목록의 105,100장과 **정확히 일치**한다(`unzip -t` 로 전수 검사)

## 서빙 (실연동 확인)

- 파일은 **리포 밖**(`personalization/data/hm/images`, gitignore)에 푼다
- Spring이 `/uploads/**` → 그 디렉터리로 정적 매핑(`UploadsConfig`, `app.uploads.dir` 기본값이 그 경로다)
- `products.image_url` 에는 **경로**(`/uploads/0108775015.jpg`)를 넣는다 — URL 전체가 아니라 경로다.
  S3·CDN으로 옮길 때 설정만 바꾸면 된다
- 파일이 없으면 404 → 화면은 그라디언트+이니셜로 폴백한다

2026-09-22 실기동 확인(단일 요청, 부하 없음):

```
GET /uploads/0108775015.jpg        → 200  image/jpeg  154,607B
GET /uploads/9999999999.jpg        → 404
GET /api/v1/products/108775015     → "imageUrl":"/uploads/0108775015.jpg"
브라우저 /product.html?id=108775015 → 상품 사진이 렌더된다(그라디언트 아님)
```

## 그전 경로 — Hugging Face 우회 (2026-09-20, 대체됨)

자격증명이 없던 동안의 우회로다. **역사로 남긴다** — 판단의 경위가 다음 사람에게 필요하다.

Hugging Face 캡션 데이터셋 `tomytjandra/h-and-m-fashion-caption` 에 이미지가 parquet 안에 들어 있었다.
`image` 컬럼이 `struct<bytes: binary, path: string>` 이고, **`path` 에 원본 파일명이 남아 있다**:

```
image[0].path  = '0108775015.jpg'        # = article_id + .jpg
image[0].bytes = b'\xff\xd8\xff\xe0...'  # JPEG 바이트
```

이게 없었으면 매핑을 못 해 못 쓸 뻔했다(캡션 데이터셋에는 `article_id` 컬럼이 없다).
이 경로로 **20,491장(19.4%)** 을 얻었다. 전체가 아니라 **부분집합**이었고, 편향도 있었다:

| 카테고리 | 이미지 있는 카드(첫 페이지 60개 기준) |
|---|---:|
| 여성복 | 23/60 |
| 아동복 | **0/60** |
| Divided | 27/60 |
| 남성복 | 18/60 |
| 스포츠 | 16/60 |

정렬별로도 달랐다 — `price_desc` 38/60, `price_asc` 3/60. 추천 상품 8개 중 3개만 이미지가 있었다.

**나중에 이 파일들이 원본과 바이트 동일하다는 것이 확인됐다** — `0108775015.jpg` 가 HF·Kaggle zip·
Kaggle API 셋 다 `154,607B` 였다. 그래서 Kaggle 번들을 풀 때 **겹치는 20,491장은 건너뛰고**
84,609장만 옮겼다(멱등: 다시 돌려도 같은 결과다).

## 함께 고친 것

`.thumb img` 에 `mix-blend-mode: luminosity; opacity: .9` 가 걸려 있었다. 데모용 picsum 사진을 배경에
녹이려던 스타일인데 **실제 상품 사진을 흐리게** 만든다. `object-fit: cover` 만 남겼다.

## 정직한 한계

- **445개에는 이미지가 없다.** 데이터셋에 없는 442개와 레거시 테스트 상품 3개다 → 화면은 그라디언트로 폴백한다
- **19.4% 시절의 "고르지 않다"는 문제는 해소됐다.** 남은 것은 데이터가 아니라 화면의 몫이다 —
  이미지 품질·해상도는 데이터셋 그대로다(리사이즈·최적화하지 않았다)
- 이미지가 없는 상품이 사라지는 건 아니다 — 팔 수 있고 추천에도 나온다. 화면만 폴백이다
- 번들 zip(30.81GB)은 `personalization/data/hm/raw/` 에 남아 있다. 지워도 이미지는 이미 풀려 있다
