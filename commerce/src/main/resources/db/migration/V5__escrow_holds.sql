-- 에스크로 홀드(자금 보류) — HELD→RELEASED/REFUNDED 생명주기. 주문당 1홀드(order_no 유니크).

    create table escrow_holds (
        amount bigint not null,
        auto_release_at datetime(6) not null,
        created_at datetime(6) not null,
        held_at datetime(6) not null,
        id bigint not null auto_increment,
        resolved_at datetime(6),
        updated_at datetime(6) not null,
        order_no varchar(200) not null,
        status enum ('HELD','RELEASED','REFUNDED') not null,
        primary key (id),
        constraint uk_escrow_order unique (order_no)
    ) engine=InnoDB;

    create index idx_escrow_status_auto_release on escrow_holds (status, auto_release_at);
