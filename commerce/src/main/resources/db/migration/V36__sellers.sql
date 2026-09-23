-- 판매자(정산으로 돈을 받는 쪽).
--
-- 왜 이제 만드나: 이 프로젝트는 단일 판매자 구조였다. 플랫폼 자신이 판매자이고 정산이
-- 일자·통화별 집계라 판매자별로 쪼개지지 않았다. 그런데 실제 정산은 <누군가에게> 한다.
-- 판매자를 넣는 것은 범위를 넓히는 일이 아니라 비현실적인 단순화를 고치는 일이다(ADR-021 개정).
--
-- 신원을 갖는 것이 범위 확장이 아닌 이유: 돈을 보내려면 누구에게 보내는지 필연적으로 안다.
-- 사업자등록번호와 계좌 없이 정산할 방법이 없다. 구매자 신원과 성격이 다르다.
CREATE TABLE sellers (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- 사업자등록번호. 제재·PEP 명단 대조의 기준이자 정산 대상의 식별자다.
    business_number     VARCHAR(20)  NOT NULL,
    legal_name          VARCHAR(200) NOT NULL COMMENT '법인명·상호. 명단 대조는 이 이름으로 한다',
    representative_name VARCHAR(100) NOT NULL COMMENT '대표자명. 개인 제재 명단과 대조한다',
    country_code        CHAR(2)      NOT NULL DEFAULT 'KR',
    -- 심사 상태. 스크리닝 결과가 여기로 들어오고, 정산 지급이 이 값을 본다.
    --   PENDING_SCREENING  등록됐고 아직 안 봤다
    --   ACTIVE             통과. 정산 가능
    --   ON_HOLD            잠재 일치가 있어 사람이 확인 중. <b>지급을 막는다</b>
    --   BLOCKED            확정 일치. 거래하지 않는다
    status              VARCHAR(24)  NOT NULL,
    payout_account      VARCHAR(64)  NULL COMMENT '정산 계좌. 심사 통과 전에는 비어 있을 수 있다',
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    -- 같은 사업자가 두 번 등록되지 않는다. 중복 등록은 심사를 두 번 받게 만들고,
    -- 그중 하나만 BLOCKED 면 다른 하나로 정산이 나간다.
    UNIQUE KEY uk_seller_business_number (business_number),
    KEY idx_seller_status (status)
) COMMENT='정산으로 돈을 받는 쪽. 지급 전 제재 스크리닝의 대상이다';

-- 스크리닝 결과. <b>판정마다 한 행</b>이고 지우지 않는다.
--
-- 왜 행으로 남기나: 제재 명단은 갱신된다. 어제 통과한 판매자가 오늘 걸릴 수 있고,
-- 그때 "언제 무엇과 대조해 통과였는지"를 못 대면 규제 대응이 안 된다.
-- 그리고 오탐률을 재려면 <b>사람이 어떻게 판정했는지</b>가 유형별로 쌓여야 한다.
CREATE TABLE seller_screenings (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    seller_id       BIGINT       NOT NULL,
    -- 대조한 이름. 법인명인지 대표자명인지에 따라 결과가 다르므로 무엇을 봤는지 남긴다.
    screened_name   VARCHAR(200) NOT NULL,
    name_kind       VARCHAR(16)  NOT NULL COMMENT 'LEGAL / REPRESENTATIVE',
    -- 명단 쪽에서 걸린 항목. 안 걸렸으면 NULL 이다.
    matched_entry   VARCHAR(200) NULL,
    -- 0~100. 어떻게 재는지는 매처가 정하고, 이 값으로 임계를 건다.
    match_score     INT          NOT NULL,
    -- 기계 판정: CLEAR(안 걸림) / POTENTIAL(사람이 봐야 함) / CONFIRMED(확정 일치)
    verdict         VARCHAR(16)  NOT NULL,
    -- 사람 판정. 오탐률의 근거가 이 칸이다. 안 봤으면 NULL.
    --   FALSE_POSITIVE  기계는 걸었는데 사람이 아니라고 했다
    --   TRUE_POSITIVE   맞았다
    human_verdict   VARCHAR(16)  NULL,
    reviewed_by     VARCHAR(100) NULL,
    list_version    VARCHAR(40)  NOT NULL COMMENT '대조한 명단의 판. 갱신 전후를 구별한다',
    screened_at     DATETIME(6)  NOT NULL,
    reviewed_at     DATETIME(6)  NULL,
    KEY idx_screening_seller (seller_id, screened_at),
    -- 오탐률 집계가 이 표를 쓴다. 그 조회를 인덱스로 받친다.
    KEY idx_screening_review (verdict, human_verdict)
) COMMENT='제재 스크리닝 판정 기록. 오탐률 측정과 규제 대응의 근거가 된다';
