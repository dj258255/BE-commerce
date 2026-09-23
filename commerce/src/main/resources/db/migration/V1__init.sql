
    create table dead_letters (
        retry_count integer not null,
        amount bigint not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        payment_id bigint not null,
        order_no varchar(64) not null,
        event_type varchar(100) not null,
        event_key varchar(200) not null,
        fail_reason varchar(1000),
        primary key (id)
    ) engine=InnoDB;

    create table event_publication (
        completion_date datetime(6),
        publication_date datetime(6),
        id binary(16) not null,
        event_type varchar(255),
        listener_id varchar(255),
        serialized_event varchar(255),
        primary key (id)
    ) engine=InnoDB;

    create table idempotency_keys (
        created_at datetime(6) not null,
        expires_at datetime(6) not null,
        id bigint not null auto_increment,
        http_method varchar(10) not null,
        request_hash varchar(64) not null,
        api_path varchar(200) not null,
        idempotency_key varchar(300) not null,
        response_body TEXT,
        status enum ('DONE','PROCESSING') not null,
        primary key (id)
    ) engine=InnoDB;

    create table internal_records (
        amount bigint not null,
        id bigint not null auto_increment,
        recorded_at datetime(6) not null,
        order_no varchar(64) not null,
        primary key (id)
    ) engine=InnoDB;

    create table ledger_entries (
        amount bigint not null,
        id bigint not null auto_increment,
        transaction_id bigint not null,
        account enum ('PG_RECEIVABLE','SALES') not null,
        direction enum ('CREDIT','DEBIT') not null,
        primary key (id)
    ) engine=InnoDB;

    create table ledger_transactions (
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        source_id bigint not null,
        source_type varchar(30) not null,
        tx_type varchar(40) not null,
        description varchar(200),
        primary key (id)
    ) engine=InnoDB;

    create table order_items (
        quantity integer not null,
        id bigint not null auto_increment,
        order_id bigint not null,
        product_id bigint not null,
        unit_price bigint not null,
        product_name varchar(200) not null,
        primary key (id)
    ) engine=InnoDB;

    create table orders (
        currency varchar(3) not null,
        created_at datetime(6) not null,
        expires_at datetime(6) not null,
        id bigint not null auto_increment,
        total_amount bigint not null,
        updated_at datetime(6) not null,
        user_id bigint not null,
        version bigint not null,
        order_no varchar(64) not null,
        status enum ('CANCELED','CREATED','EXPIRED','FAILED','PAID','PAYMENT_IN_PROGRESS','PENDING_PAYMENT') not null,
        primary key (id)
    ) engine=InnoDB;

    create table payment_history (
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        payment_id bigint not null,
        reason varchar(500),
        from_status enum ('ABORTED','CANCELED','DONE','EXPIRED','IN_PROGRESS','PARTIAL_CANCELED','READY','UNKNOWN') not null,
        to_status enum ('ABORTED','CANCELED','DONE','EXPIRED','IN_PROGRESS','PARTIAL_CANCELED','READY','UNKNOWN') not null,
        triggered_by enum ('ADMIN','POLLING','RECOVERY_BATCH','USER','WEBHOOK') not null,
        primary key (id)
    ) engine=InnoDB;

    create table payments (
        amount bigint not null,
        approved_at datetime(6),
        balance_amount bigint not null,
        id bigint not null auto_increment,
        requested_at datetime(6) not null,
        version bigint not null,
        method varchar(30),
        pg_provider varchar(30) not null,
        order_no varchar(64) not null,
        payment_key varchar(200),
        unknown_reason varchar(200),
        status enum ('ABORTED','CANCELED','DONE','EXPIRED','IN_PROGRESS','PARTIAL_CANCELED','READY','UNKNOWN') not null,
        primary key (id)
    ) engine=InnoDB;

    create table processed_events (
        id bigint not null auto_increment,
        processed_at datetime(6) not null,
        consumer varchar(100) not null,
        event_key varchar(200) not null,
        primary key (id)
    ) engine=InnoDB;

    create table products (
        price bigint not null,
        product_id bigint not null,
        name varchar(200) not null,
        primary key (product_id)
    ) engine=InnoDB;

    create table reconciliation_results (
        external_amount bigint,
        id bigint not null auto_increment,
        internal_amount bigint,
        reconciled_at datetime(6) not null,
        order_no varchar(64),
        result enum ('AMOUNT_MISMATCH','EXTERNAL_ONLY','INTERNAL_ONLY','MATCHED') not null,
        status enum ('AUTO_RESOLVED','PENDING') not null,
        primary key (id)
    ) engine=InnoDB;

    create table settlement_items (
        confirmed_date date not null,
        amount bigint not null,
        id bigint not null auto_increment,
        payment_id bigint not null,
        order_no varchar(64) not null,
        status enum ('PENDING','SETTLED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table settlements (
        item_count integer not null,
        settlement_date date not null,
        created_at datetime(6) not null,
        fee_amount bigint not null,
        gross_amount bigint not null,
        id bigint not null auto_increment,
        net_amount bigint not null,
        status enum ('CREATED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table stock (
        quantity integer not null,
        product_id bigint not null,
        version bigint not null,
        primary key (product_id)
    ) engine=InnoDB;

    create table webhook_events (
        id bigint not null auto_increment,
        processed_at datetime(6),
        received_at datetime(6) not null,
        event_type varchar(60),
        external_event_id varchar(100) not null,
        fail_reason varchar(500),
        raw_payload TEXT,
        status enum ('FAILED','PROCESSED','RECEIVED','SKIPPED') not null,
        primary key (id)
    ) engine=InnoDB;

    alter table idempotency_keys 
       add constraint uk_idem unique (idempotency_key, api_path, http_method);

    alter table internal_records 
       add constraint uk_internal_record_order unique (order_no);

    alter table ledger_transactions 
       add constraint uk_ledger_tx_source unique (tx_type, source_type, source_id);

    alter table orders 
       add constraint uk_orders_order_no unique (order_no);

    alter table processed_events 
       add constraint uk_processed unique (event_key, consumer);

    alter table settlement_items 
       add constraint uk_settlement_item_payment unique (payment_id);

    alter table settlements 
       add constraint uk_settlement_date unique (settlement_date);

    alter table webhook_events 
       add constraint uk_webhook_external_id unique (external_event_id);

    alter table ledger_entries 
       add constraint FKewye0l6hecsa42kd4ylfiuo0h 
       foreign key (transaction_id) 
       references ledger_transactions (id);

    alter table order_items 
       add constraint FKbioxgbv59vetrxe0ejfubep1w 
       foreign key (order_id) 
       references orders (id);

    alter table payment_history 
       add constraint FKsf1llvalsv3uwq221wvn49jc8 
       foreign key (payment_id) 
       references payments (id);

-- 배치 스캔용 보조 인덱스 (09-ERD 설계)
create index idx_payments_status_requested on payments (status, requested_at);
create index idx_payments_order on payments (order_no);
create index idx_orders_status_created on orders (status, created_at);
create index idx_payment_history_payment on payment_history (payment_id, created_at);
create index idx_settlement_item_date_status on settlement_items (confirmed_date, status);
create index idx_dead_letters_created on dead_letters (created_at);
