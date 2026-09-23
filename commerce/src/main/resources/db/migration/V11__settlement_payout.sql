-- 정산 고도화: 수수료 부가세·지급예정일·지급확정 시각을 정산 집계에 추가한다.
--
-- 컬럼:
--   fee_vat_amount : 수수료 부가세(수수료의 10%). 레거시는 0으로 백필하므로 net = gross - fee 불변식이
--                    그대로 유지된다(net 재계산 불필요 — feeVat=0). DEFAULT 0은 후속 앱 INSERT가 항상
--                    값을 실어 보내므로 무해하다.
--   payout_date    : 지급예정일. 신규 정산은 앱이 영업일(주말 skip)로 계산해 채우고, 레거시는 아래에서
--                    단순 +2일로 근사 백필한다(영업일 아님).
--   paid_out_at    : 지급 확정 시각(nullable). 어드민이 지급을 확정한 순간 채워진다.

alter table settlements
    add column fee_vat_amount bigint not null default 0,
    add column payout_date date null,
    add column paid_out_at datetime(6) null;

-- 레거시 백필: 지급예정일이 없던 과거 정산은 정산일 +2일로 근사한다(신규는 앱이 영업일로 계산).
update settlements set payout_date = date_add(settlement_date, interval 2 day) where payout_date is null;

-- 백필 완료 후 NOT NULL로 확정한다(신규 집계는 항상 payoutDate를 채운다 → ddl-auto=validate 통과).
alter table settlements modify payout_date date not null;

-- status enum에 PAID_OUT을 추가한다(Java enum 선언 순서 CREATED,PAID_OUT와 일치 → validate 통과).
alter table settlements modify status enum ('CREATED','PAID_OUT') not null;
