-- 개발/부하테스트용 시드 데이터
insert into products (product_id, name, price) values
  (1, '테스트 상품 A', 10000),
  (2, '테스트 상품 B', 5000),
  (3, '한정판 상품', 30000);

insert into stock (product_id, quantity, version) values
  (1, 1000000, 0),
  (2, 1000000, 0),
  (3, 100, 0);          -- 선착순 시나리오용 소량 재고
