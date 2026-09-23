-- 회원(member) 도메인: JPA 기반 이메일 가입/로그인 도입.
--
-- 기존 인증은 InMemoryUserDetailsManager의 데모 계정(admin/admin2/"1"/"2")뿐이라 실 회원이 없었다.
-- 이 테이블은 이메일로 가입한 실 회원을 담는다. password_hash에는 BCrypt 해시만 저장한다(원문 금지).
--
-- 핵심 계약: 시스템 전체가 principal.getName()을 Long.parseLong으로 파싱해 userId(소유권 키)로 쓴다.
-- 로그인 후 JWT subject는 이 members.id(숫자)가 되므로, 인메모리 데모 유저 "1"/"2"와 principal이
-- 충돌하면 안 된다. 그래서 아래에서 AUTO_INCREMENT를 1000으로 올려 신규 회원 id가 최소 1000부터
-- 시작하도록 한다(데모 유저 1/2와의 principal 충돌 방지).
create table members (
    id bigint not null auto_increment,
    email varchar(255) not null,
    password_hash varchar(100) not null,
    role varchar(20) not null,
    created_at datetime(6) not null,
    primary key (id)
) engine=InnoDB;

create unique index uk_members_email on members (email);

-- 신규 회원 id는 1000부터 — 인메모리 데모 유저 principal("1"/"2")과 충돌하지 않게 한다.
alter table members auto_increment = 1000;
