-- 숏폼 시청 신호(R27) — 영상 하나를 본 한 번의 시청(세션)을 요약해 요청 단위 한 행으로 남긴다.
--
-- 왜 기존 user_activities(개인화 Kafka/CDC 경로)를 재사용하지 않는가: 그 표는 명시적으로 합성
-- 생성기 전용이고(UserActivity.SOURCE_SYNTHETIC, PersonalizationController 주석 "실제 화면이
-- 부르는 표면이 아니다") 로그인 사용자만 허용하며 활동 유형도 CLICK/VIEW 두 가지뿐이다 — R27이
-- 요구하는 비로그인 익명 식별자·시청시간·완료·다시보기·건너뛰기·상품탭을 담을 수 없다. 대신
-- ADR-043(홈 노출 로그)의 선례(요청 단위 한 행, Outbox/Kafka에 태우지 않음)를 media 모듈
-- 자신의 표로 따른다. 근거는 ADR-083에 적었다.
--
-- userId/anonymousId는 정확히 하나만 채워진다(ViewerIdentity와 같은 규칙). FK는 걸지 않는다
-- (이 저장소의 "논리적 FK + 인덱스" 관례 — user_activities·home_impressions와 같다).

create table short_view_events (
    id                 bigint       not null auto_increment,
    short_video_id     bigint       not null,
    user_id            bigint       null,
    anonymous_id       varchar(64)  null,
    watch_seconds      int          not null,
    completed          boolean      not null,
    replay_count       int          not null,
    skipped_within3s   boolean      not null,
    product_tag_tapped boolean      not null,
    occurred_at        datetime(6)  not null,
    created_at         datetime(6)  not null,
    primary key (id)
) engine=InnoDB;

-- R28 신호 1(완료율): 영상별로 집계하는 질의가 기본 동선이다.
create index idx_short_view_events_video on short_view_events (short_video_id);
-- R28 신호 3(상품 선호 일치): 시청자별 최근 관심 영상을 되짚는 질의.
create index idx_short_view_events_user on short_view_events (user_id, occurred_at);
create index idx_short_view_events_anon on short_view_events (anonymous_id, occurred_at);
