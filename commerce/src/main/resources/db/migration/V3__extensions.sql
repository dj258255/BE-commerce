-- 확장 모듈 테이블(point·subscription·wallet·fraud 없음(무상태)·audit·receipt·va)

    create table audit_logs (
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        target_type varchar(40) not null,
        action varchar(60) not null,
        target_id varchar(64) not null,
        actor varchar(100) not null,
        detail varchar(1000),
        primary key (id)
    ) engine=InnoDB;

    create table billing_keys (
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        user_id bigint not null,
        customer_key varchar(64) not null,
        billing_key varchar(200) not null,
        primary key (id)
    ) engine=InnoDB;

    create table cash_receipts (
        amount bigint not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        issued_at datetime(6),
        receipt_type varchar(20) not null,
        receipt_key varchar(100),
        order_no varchar(200) not null,
        status enum ('CANCELED','FAILED','ISSUED','REQUESTED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table dunning_attempts (
        attempt_no integer not null,
        next_retry_at date,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        subscription_id bigint not null,
        result enum ('HARD_DECLINE','SOFT_DECLINE','SUCCESS') not null,
        primary key (id)
    ) engine=InnoDB;

    create table point_accounts (
        balance bigint not null,
        user_id bigint not null,
        version bigint not null,
        primary key (user_id)
    ) engine=InnoDB;

    create table point_histories (
        amount bigint not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        user_id bigint not null,
        order_no varchar(64) not null,
        type enum ('REFUND','RESTORE','USE') not null,
        primary key (id)
    ) engine=InnoDB;

    create table subscriptions (
        current_period_start date not null,
        next_billing_date date not null,
        id bigint not null auto_increment,
        plan_amount bigint not null,
        user_id bigint not null,
        version bigint not null,
        billing_key varchar(200) not null,
        status enum ('ACTIVE','CANCELED','EXPIRED','IN_GRACE_PERIOD','ON_HOLD') not null,
        primary key (id)
    ) engine=InnoDB;

    create table virtual_accounts (
        amount bigint not null,
        created_at datetime(6) not null,
        deposited_at datetime(6),
        due_date datetime(6) not null,
        id bigint not null auto_increment,
        version bigint not null,
        bank_code varchar(10) not null,
        account_number varchar(30) not null,
        order_no varchar(64) not null,
        payment_key varchar(200),
        status enum ('CANCELED','DONE','EXPIRED','WAITING_FOR_DEPOSIT') not null,
        primary key (id)
    ) engine=InnoDB;

    create table wallet_accounts (
        balance bigint not null,
        user_id bigint not null,
        version bigint not null,
        primary key (user_id)
    ) engine=InnoDB;

    create table wallet_transactions (
        amount bigint not null,
        balance_after bigint not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        user_id bigint not null,
        type enum ('CHARGE','REFUND','USE') not null,
        primary key (id)
    ) engine=InnoDB;

    alter table billing_keys 
       add constraint UK6udxck08qejmex3kjdt2g29gr unique (billing_key);

