-- 플랫폼 자신을 판매자 한 줄로 등록하고, seller_id 를 NOT NULL 로 바꾼다.
--
-- <b>V37 이 NULL 을 고른 이유는 옳았지만 대상이 달랐다.</b> 그때 거절한 것은 "기존 행에
-- 없는 판매자를 소급해 지어내기"다. 그런데 플랫폼은 지어낸 판매자가 아니다 — 실제로
-- 물건을 판 쪽이고, 사업자등록번호를 갖는 법적 주체다. 그 사실을 한 줄로 적으면
-- NULL 이 하던 일을 실제 행이 대신한다.
--
-- <b>NULL 이 만든 비용</b>
--   V37  유니크 키에 seller_id 를 넣었는데 MySQL 이 NULL 을 서로 다르게 봐서 뚫렸다
--   V42  생성 컬럼 seller_key = COALESCE(seller_id, 0) 로 그 구멍만 덮었다
--   코드 존재 검사가 `:sellerId is null and s.sellerId is null or ...` 로 갈렸고,
--        집계도 groupingBy 를 못 써서 손으로 맵을 채운다(널 키를 거부한다)
-- 셋 다 "판매자를 모른다"가 아니라 "판매자가 플랫폼이다"를 NULL 로 적은 대가다.
--
-- <b>지급 게이트 동작은 안 바꾼다.</b> 게이트가 막는 것은 <외부로 나가는 지급>이고
-- 플랫폼 직판은 거기 해당하지 않는다. 지금까지 NULL 검사가 하던 그 판단을
-- 플랫폼 판매자 id 검사가 그대로 이어받는다. 컬럼 nullable 을 고치면서
-- <b>누가 제재 명단 대조를 받는지까지 조용히 바뀌면 안 된다.</b>
--
-- <b>id 를 1 로 박는다.</b> 이미 다른 판매자가 쓰고 있으면 여기서 중복 키로 멈춘다.
-- 돈이 나가는 자리라 조용히 넘어가는 것보다 기동이 실패하는 쪽이 낫다.
INSERT INTO sellers (id, business_number, legal_name, representative_name,
                     country_code, status, created_at, updated_at)
VALUES (1, 'PLATFORM', '플랫폼 직판', '플랫폼', 'KR', 'ACTIVE', NOW(6), NOW(6));

-- 기존 NULL 은 전부 플랫폼 직판이다. V37 주석이 그렇게 적어 뒀고 집계도 그렇게 세 왔다.
UPDATE settlement_items SET seller_id = 1 WHERE seller_id IS NULL;
UPDATE settlements      SET seller_id = 1 WHERE seller_id IS NULL;

-- 유니크 키를 생성 컬럼에서 seller_id 로 되돌린다. 인덱스를 먼저 떼야 컬럼을 지울 수 있다.
ALTER TABLE settlements DROP INDEX uk_settlement_date_currency_seller;
ALTER TABLE settlements DROP COLUMN seller_key;

ALTER TABLE settlements
    MODIFY COLUMN seller_id BIGINT NOT NULL COMMENT '정산을 받을 판매자. 플랫폼 직판도 자기 판매자 행을 갖는다';

ALTER TABLE settlements
    ADD CONSTRAINT uk_settlement_date_currency_seller UNIQUE (settlement_date, currency, seller_id);

ALTER TABLE settlement_items
    MODIFY COLUMN seller_id BIGINT NOT NULL COMMENT '정산을 받을 판매자. 플랫폼 직판도 자기 판매자 행을 갖는다';
