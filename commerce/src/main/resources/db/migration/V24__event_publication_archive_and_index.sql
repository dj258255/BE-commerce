-- Outbox(event_publication) 무한 증식과 풀스캔을 함께 고친다.
--
-- 발견 경위: 다계정 스파이크 실험을 반복하다 같은 설정의 실행이 회차마다 느려졌다(600 VU에서
-- 처리량 75/s → 93/s, p95 2.98s → 4.92s로 재현이 안 됨). 원인은 부하가 아니라 테이블이었다.
-- 실험 6회 뒤 event_publication이 150,372행(완료 103,495 / 미완료 42,335)까지 자라 있었다.
--
-- 두 가지가 겹쳐 있었다.
--
-- (1) 완료 행이 지워지지 않는다.
--     spring.modulith.events.completion-mode 기본값이 update — 완료 시 completion_date만 채우고
--     행은 영원히 남는다. 발행량에 비례해 무한히 자란다.
--
-- (2) 인덱스가 PK 하나뿐이라 완료 처리가 매번 풀스캔이다.
--     Modulith가 리스너 완료마다 도는 쿼리는
--       update ... set completion_date=? where serialized_event=? and listener_id=? and completion_date is null
--     인데 두 컬럼 모두 인덱스가 없다. EXPLAIN: type=ALL, key=NULL, rows=150372.
--     PaymentConfirmedEvent에는 리스너가 5개라 결제 한 건당 이 풀스캔이 5회 돈다.
--     즉 결제 지연이 "그동안 처리한 이벤트 총량"에 비례해 나빠진다 — 부하와 무관하게.
--
-- 고치는 방법도 두 겹이다. 아카이브가 구조적 해결(테이블 크기에 상한이 생긴다)이고,
-- 인덱스는 미완료가 쌓였을 때(브로커 장애·재기동 대기 등)를 위한 안전망이다. 아카이브만 하면
-- 평시엔 빠르지만 장애로 미완료가 쌓인 순간 다시 느려진다 — 하필 가장 급할 때.

create table event_publication_archive (
    completion_date  datetime(6),
    publication_date datetime(6),
    id               binary(16) not null,
    event_type       varchar(255),
    listener_id      varchar(255),
    serialized_event varchar(255),
    primary key (id)
) engine=InnoDB;

-- 완료 처리 핫패스. listener_id를 앞에 두는 이유: 같은 이벤트를 여러 리스너가 나눠 갖는 구조라
-- (PaymentConfirmedEvent 5개) listener_id의 선택도가 낮아 보이지만, 이 쿼리는 항상 두 값이 함께
-- 들어오는 등치 조건이라 순서보다 "둘 다 인덱스에 있다"가 중요하다.
-- serialized_event는 prefix(191) — utf8mb4에서 191*4=764바이트로 InnoDB 인덱스 한도에 안전하고,
-- 실측 최대 직렬화 길이가 116자라 prefix가 사실상 전체를 덮는다.
create index idx_event_pub_lookup
    on event_publication (listener_id, serialized_event(191));

-- 재기동 시 미완료 재발행(republish-outstanding-events-on-restart: true)이 쓰는 경로.
-- completion_date is null + order by publication_date asc를 인덱스만으로 만족시킨다.
create index idx_event_pub_incomplete
    on event_publication (completion_date, publication_date);

-- 이미 쌓인 완료 행을 아카이브로 옮긴다. 옮기지 않으면 아카이브 모드로 바꿔도 과거 행이 그대로
-- 남아 테이블이 계속 크고, 위 인덱스가 지켜야 할 크기 상한이 생기지 않는다.
-- 주의: 단일 문이라 행 수가 아주 많으면 오래 잠근다. 운영에서 수백만 행 규모라면 이 두 문 대신
-- id 범위로 나눠 배치로 옮기고, 이 마이그레이션은 테이블·인덱스 생성까지만 담당해야 한다.
insert into event_publication_archive
    (id, completion_date, publication_date, event_type, listener_id, serialized_event)
select id, completion_date, publication_date, event_type, listener_id, serialized_event
  from event_publication
 where completion_date is not null;

delete from event_publication where completion_date is not null;
