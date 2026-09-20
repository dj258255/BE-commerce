-- 데모 카탈로그를 은퇴시킨다.
--
-- 왜: V54가 심은 36개 데모 상품은 picsum.photos의 **랜덤 사진**을 쓴다("USB-C 고속 충전 케이블"에
-- 딸기 사진이 붙는다). 실제 H&M 카탈로그가 들어온 지금, 이건 스토어프론트를 가짜로 보이게 하는
-- 잔재다 — 카테고리 목록에 8개짜리 '디지털·가전'이 39,737개짜리 '여성복'과 나란히 뜨고,
-- '신상품' 정렬은 데모 상품(created_at이 시드 시각)으로 채워진다.
--
-- 상품 1~3은 **남긴다.** k6 부하 스크립트가 productId=1을 쓰고, 재고 경합 시나리오가
-- product 3(소량 100)을 쓴다 — V2의 의도다. 다만 탐색 표면에서는 뺀다:
-- category_code를 NULL로, created_at을 과거로 두어 카테고리 목록과 '신상품' 정렬에 뜨지 않게 한다.
-- 이름도 '테스트 상품'으로 되돌린다 — 데모 상품 이름을 달고 있으면 여전히 상품처럼 보인다.
-- 주문·결제 경로에서는 그대로 산다(가격과 재고는 건드리지 않는다).

delete from stock where product_id between 4 and 36;
delete from products where product_id between 4 and 36;
delete from categories where code in ('digital', 'fashion', 'living', 'beauty', 'food', 'hobby');

update products
   set name = '테스트 상품 A', category_code = null, brand = null, description = null,
       image_url = null, featured = 0, created_at = '2018-09-20 00:00:00'
 where product_id = 1;

update products
   set name = '테스트 상품 B', category_code = null, brand = null, description = null,
       image_url = null, featured = 0, created_at = '2018-09-20 00:00:00'
 where product_id = 2;

update products
   set name = '한정판 상품', category_code = null, brand = null, description = null,
       image_url = null, featured = 0, created_at = '2018-09-20 00:00:00'
 where product_id = 3;
