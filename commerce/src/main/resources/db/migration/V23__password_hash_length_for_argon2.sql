-- Argon2id 해시를 담을 수 있게 컬럼을 넓힌다 (ADR-009).
--
-- 실측 길이:
--   {bcrypt}$2a$10$...                            68자
--   {argon2}$argon2id$v=19$m=19456,t=2,p=1$...   105자   ← varchar(100)에 안 들어간다
--
-- 알고리즘만 바꾸고 컬럼을 안 넓히면 가입이 "Data too long"으로 터진다. 인코더 단위 테스트는
-- DB를 타지 않아 초록불이었다 — 스키마가 따라와야 하는 변경이라는 걸 실측으로 확인하고 넓힌다.
--
-- 255로 잡는 이유: 나중에 파라미터를 올리면(메모리·iterations) 인코딩 문자열이 더 길어진다.
-- 그때마다 마이그레이션을 다시 쓰지 않도록 여유를 둔다.
alter table members
    modify column password_hash varchar(255) not null;
