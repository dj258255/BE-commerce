-- 강제취소 요청(maker-checker 2인 승인) — REQUESTED→EXECUTED/REJECTED. 요청자≠승인자 강제.

    create table force_cancel_requests (
        cancel_amount bigint not null,
        payment_id bigint not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        requested_at datetime(6) not null,
        resolved_at datetime(6),
        updated_at datetime(6) not null,
        approved_by varchar(100),
        requested_by varchar(100) not null,
        reason varchar(300),
        status enum ('REQUESTED','EXECUTED','REJECTED') not null,
        primary key (id)
    ) engine=InnoDB;

    create index idx_fcr_status on force_cancel_requests (status);
