-- 홈 노출 기록(M7) — **무엇이 화면에 나갔는가**를 요청 단위로 남긴다.
--
-- 왜 필요한가: 홈이 무엇을 보여줬는지 모르면 "왜 이 화면인가"를 복원할 수 없고, 나중에 클릭·구매와
-- 겹쳐 보는 것도 불가능하다(개인화 아키텍처의 "노출 이벤트"). 응답의 `stats` 는 그 요청 하나의
-- 요약이고, 이 표는 **여러 요청을 모아 보는 자리**다.
--
-- 왜 **항목당 한 행이 아니라 요청당 한 행**인가: 홈 한 번에 항목이 20개 나간다. 항목당 행이면
-- 30 req/s 에서 초당 600행이고, 그 쓰기가 홈 지연에 붙는다. 요청당 한 행이면 초당 30행이다.
-- 대가는 **조인이 불편해지는 것**이다(아이템별로 보려면 `item_ids` 를 풀어야 한다) — 그 대가를
-- 받아들이는 이유와, 훈련 데이터라면 어디로 가야 하는지는 ADR-043 에 적었다.
--
-- 왜 Outbox/Kafka 가 아닌가: 노출은 **도메인 상태가 아니라 관측 데이터**다. Outbox 는 "이 이벤트가
-- 반드시 처리돼야 한다"를 보장하는 장치고, 이 저장소는 그 비대화로 이미 고생했다(부하 6회에 15만 행,
-- 완료 처리 쿼리가 그 전체를 풀스캔). 노출을 거기 태우면 같은 문제를 다시 만든다.

create table home_impressions (
    id                   bigint       not null auto_increment,
    user_id              bigint       not null,
    -- 그 응답이 어디서 왔는가: MODEL | FALLBACK
    source               varchar(20)  not null,
    fallback_reason      varchar(40)  null,
    context_staleness_ms bigint       null,
    total_ms             bigint       not null,
    model_ms             bigint       not null,
    constraint_ms        bigint       not null,
    row_count            int          not null,
    item_count           int          not null,
    -- 노출된 상품 id 를 **순서 그대로** 담는다(쉼표 구분). 순위가 곧 화면에서의 위치다.
    item_ids             varchar(4000) not null,
    -- 조립이 버린 것(중복·품절·다양성 상한·미매칭) — "왜 이 화면인가"를 나중에 복원하려면 필요하다.
    stats                varchar(300) null,
    created_at           datetime(6)  not null,
    primary key (id)
) engine=InnoDB;

-- 사용자별로 최근 노출을 훑는 질의가 예상되는 형태(개인화 홈의 기본 조회 동선).
create index idx_home_impressions_user on home_impressions (user_id, created_at);
