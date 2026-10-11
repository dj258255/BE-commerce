-- 라이브 방송 상태머신(R1·R2·R3). 스트림 키 발급부터 송출 시작·끊김·재접속 유예·종료까지의
-- 현재 상태를 적재한다. 상품 고정·주문(다음 단계)은 여기 없다 — media는 주문·결제·재고
-- 확정 로직을 갖지 않는다(R32).

    create table live_broadcasts (
        seller_id bigint not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        updated_at datetime(6) not null,
        started_at datetime(6),
        ended_at datetime(6),
        disconnected_at datetime(6),
        stream_key varchar(32) not null,
        title varchar(200) not null,
        status enum ('SCHEDULED','LIVE','ENDED') not null,
        primary key (id)
    ) engine=InnoDB;

    create unique index uk_live_broadcast_stream_key on live_broadcasts (stream_key);
    create index idx_live_broadcast_seller on live_broadcasts (seller_id);
    -- 재접속 유예 만료 스캔(LiveBroadcastGraceScheduler)의 핫 패스 — LIVE이고 끊긴 적 있는
    -- 방송만 좁혀서 본다.
    create index idx_live_broadcast_status_disconnect on live_broadcasts (status, disconnected_at);
