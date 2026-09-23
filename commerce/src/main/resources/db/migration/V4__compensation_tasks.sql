-- 보상 태스크(승인 후 재고 부족 시 카드 망취소) — durable 재시도 큐

    create table compensation_tasks (
        amount bigint not null,
        max_retries integer not null,
        retry_count integer not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        next_attempt_at datetime(6) not null,
        updated_at datetime(6) not null,
        reason varchar(300),
        order_no varchar(200) not null,
        last_error varchar(500),
        type enum ('NETWORK_CANCEL') not null,
        status enum ('PENDING','DONE','FAILED') not null,
        primary key (id)
    ) engine=InnoDB;

    create index idx_comp_status_next on compensation_tasks (status, next_attempt_at);
