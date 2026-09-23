-- 민감 필드 암호화 실적용 (envelope). 완성해 둔 crypto 인프라(@Convert)를 실제 컬럼에 붙이면서,
-- 평문보다 훨씬 긴 암호문을 담도록 컬럼을 넓힌다. 조회되는 유니크 필드(빌링키)는 블라인드 인덱스로
-- 조회·유니크를 대체한다. ddl-auto=validate가 엔티티 @Column과 정확히 대조하므로 길이·nullable·유니크를 일치시킨다.

-- 1) 가상계좌 계좌번호: 값으로 조회하지 않음 → 단순 암호화만. varchar(30) → varchar(255).
alter table virtual_accounts
    modify account_number varchar(255) not null;

-- 2) 구독의 빌링키 참조: 값으로 조회하지 않음 → 단순 암호화만. varchar(200) → varchar(600).
alter table subscriptions
    modify billing_key varchar(600) not null;

-- 3) 빌링키: 암호문 저장 + 블라인드 인덱스로 유니크/조회 대체.
--    기존 unique 인덱스(V3에서 Hibernate가 생성)를 제거한다 — 암호문은 매번 달라 유니크가 무의미하다.
alter table billing_keys
    drop index UK6udxck08qejmex3kjdt2g29gr;
alter table billing_keys
    modify billing_key varchar(600) not null;

-- 기존(마이그레이션 이전) 빌링키 행은 데모 데이터이며 평문이라 블라인드 인덱스를 백필할 수 없다.
-- 신규 발급 경로가 완전히 동작하므로, 인덱스 없는 레거시 행을 남겨 유니크 제약을 깨뜨리는 대신 정리한다
-- (빌링키는 재발급하면 된다). subscriptions는 FK가 아닌 문자열 참조라 정합성 문제 없음.
delete from billing_keys;

-- 결정적 블라인드 인덱스(HMAC-SHA256 hex=64자) 컬럼 추가 — 암호화로 잃은 조회·유니크를 대체한다.
alter table billing_keys
    add column billing_key_index varchar(64) not null;
alter table billing_keys
    add constraint uk_billing_key_index unique (billing_key_index);
