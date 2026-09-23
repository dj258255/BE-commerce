-- 분쟁/차지백 — 카드 결제의 사후 처리. 차지백 수신으로 분쟁을 개시하고(OPEN), 증빙 제출
-- (EVIDENCE_SUBMITTED)·승패(WON/LOST)로 진행한다. 패소 시 앱이 원장 역분개 이벤트를 발행한다.
--
-- chargeback_id UNIQUE = 멱등키: PG의 중복 차지백 웹훅이 두 번째 분쟁 행을 만들지 못하게 DB가 차단.
-- payment_id는 웹훅이 안 실어줄 수 있어 nullable. status enum 순서는 Java enum 선언 순서
-- (OPEN,EVIDENCE_SUBMITTED,WON,LOST)와 일치 → ddl-auto=validate 통과.

    create table disputes (
        amount bigint not null,
        payment_id bigint,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        resolved_at datetime(6),
        respond_by_deadline datetime(6) not null,
        chargeback_id varchar(200) not null,
        order_no varchar(200) not null,
        reason varchar(500),
        evidence_memo varchar(1000),
        status enum ('OPEN','EVIDENCE_SUBMITTED','WON','LOST') not null,
        primary key (id)
    ) engine=InnoDB;

    alter table disputes
        add constraint uk_dispute_chargeback unique (chargeback_id);
