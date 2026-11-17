SET NAMES utf8mb4;

-- 신한걸음 데모 시드 (01_hanstep_schema.sql, 02_hanstep_seed.sql 실행 후 실행)
-- 시연용 테스트 사용자 1명(Mark Anthony Reyes, 필리핀 E-9 제조업, 가상 인물·합성 데이터)과 해외송금 받는 분 2명만 넣음.
--   E-7-4 점수 계산(비자 프로필·근무·소득·증빙), 금융 Mock·해외송금 데모를 이 한 명으로 한다.
-- 계좌·거래내역 12개월 Mock은 resources/mock/mydata/demo-e9-ph-reyes 를 MYD-02 로 적재한다(이 파일에 넣지 않음).
--   청약 계좌(INSTALLMENT)는 없다(대안신용 hasSubscription=false).
-- 통신비·건강보험료·공과금 납부 내역(대안신용 연체 입력)은 이 파일에 넣는다(맨 아래, Mock).

-- 데모 사용자: /api/v1/auth/entry 의 entryToken = external_user_key. 고용기업 추천서만 아직 내지 않았다.
-- 입국(현재 체류 시작) 2022-12-05, 근무 시작 2022-12-12. 체류 4년(48개월)은 2026-12-05 에 채운다.
-- E-7-4 기대 결과(E74-2026-v1): 확정 235점, 가능 285점(증빙 필요 REC_EMPLOYER·REQ_EMPLOYER_REC).
--   기준일 ~2026-12-04: INELIGIBLE(REQ_STAY_PERIOD 47/48개월), 2026-12-05~: NEEDS_EVIDENCE.
-- 감점·제외 6문항은 저장하지 않는다(계산 요청 때 legalDeclaration 으로 보낸다).
INSERT INTO users (external_user_key, display_name, preferred_language) VALUES
 ('demo-e9-ph-reyes', 'MARK ANTHONY REYES', 'en');

INSERT INTO visa_profiles
 (user_id, visa_type_id, stay_expires_on, nationality_code, birth_date, current_stay_started_on, prior_stay_months, evidence_status)
SELECT u.id, vt.id, '2027-10-04', 'PHL', '1995-08-15', '2022-12-05', 0, 'VERIFIED'
FROM users u JOIN visa_types vt ON vt.code = 'E-9' WHERE u.external_user_key = 'demo-e9-ph-reyes';

INSERT INTO employment_histories
 (user_id, employer_name, industry_code, workplace_region_code, is_depopulation_area, started_on, contract_end_on, contract_annual_salary, verification_status)
SELECT u.id, 'DEMO-PH-METAL', 'MANUFACTURING', '48250', FALSE, '2022-12-12', '2029-03-13', 31500000, 'VERIFIED'
FROM users u WHERE u.external_user_key = 'demo-e9-ph-reyes';

INSERT INTO income_records (user_id, income_year, annual_amount, source, verification_status)
SELECT u.id, 2024, 30400000, 'DOCUMENT', 'VERIFIED' FROM users u WHERE u.external_user_key = 'demo-e9-ph-reyes';
INSERT INTO income_records (user_id, income_year, annual_amount, source, verification_status)
SELECT u.id, 2025, 32100000, 'DOCUMENT', 'VERIFIED' FROM users u WHERE u.external_user_key = 'demo-e9-ph-reyes';

INSERT INTO visa_evidence (user_id, evidence_type, level_value, issued_on, expires_on, status)
SELECT u.id, 'TOPIK', '3', '2026-04-12', '2028-04-12', 'VERIFIED' FROM users u WHERE u.external_user_key = 'demo-e9-ph-reyes';
INSERT INTO visa_evidence (user_id, evidence_type, status)
SELECT u.id, 'RECOMMENDATION_EMPLOYER', 'DECLARED' FROM users u WHERE u.external_user_key = 'demo-e9-ph-reyes';
INSERT INTO visa_evidence (user_id, evidence_type, level_value, issued_on, status)
SELECT u.id, 'DRIVER_LICENSE', '2종 보통', '2024-05-20', 'VERIFIED' FROM users u WHERE u.external_user_key = 'demo-e9-ph-reyes';

-- 해외송금 받는 분 2명: 어머니(BDO)와 본인 명의 필리핀 계좌(BPI). TR-001 송금 화면·AI-002 재질의에서 사용.
-- name_en·bank_name 은 Mock 거래내역(bank-shinhan.json)의 counterparty_name·counterparty_bank 와 같은 표기로 맞춤.
-- relationship: 데모 시나리오에서는 사용자가 이전 대화에서 이미 확인했다고 가정한다(관계는 추정해 저장하지 않음).
-- swift_code: BDO·BPI 공개 SWIFT 코드. 계좌번호는 마스킹 값만 저장함.
INSERT INTO remittance_recipients
 (user_id, external_recipient_key, name_en, relationship, country_code, currency, bank_name, swift_code, account_no_masked, source, is_mock, relationship_confirmed_at)
SELECT u.id, 'mock-recipient-mother-bdo', 'LORNA REYES', 'MOTHER', 'PH', 'PHP', 'BDO', 'BNORPHMM', '****5566', 'MOCK', TRUE, '2026-11-01 00:00:00.000'
FROM users u WHERE u.external_user_key = 'demo-e9-ph-reyes';
INSERT INTO remittance_recipients
 (user_id, external_recipient_key, name_en, relationship, country_code, currency, bank_name, swift_code, account_no_masked, source, is_mock, relationship_confirmed_at)
SELECT u.id, 'mock-recipient-self-bpi', 'MARK ANTHONY REYES', 'SELF', 'PH', 'PHP', 'BPI', 'BOPIPHMM', '****7788', 'MOCK', TRUE, '2026-11-01 00:00:00.000'
FROM users u WHERE u.external_user_key = 'demo-e9-ph-reyes';

-- 청구 납부 내역(대안신용 연체 입력): 통신비·건강보험료·공과금(도시가스) 3종 × 2026-05~10 = 18행, Mock 수집 배치 1건에 묶음.
-- 임시값: 모두 납부 기한 당일 자동이체로 냈다(연체 0회). 금액도 임시값이고, 도시가스만 bank-shinhan.json 의
--   25일 70,000원 출금과 같다(2026-03 의 늦은 출금은 이 6개월 밖).
INSERT INTO ingestion_batches
 (user_id, source_type, is_mock, request_key, status, coverage_from, coverage_to, record_count, started_at, finished_at)
SELECT u.id, 'MOCK', TRUE, 'seed-bill-payments', 'SUCCEEDED', '2026-05-01', '2026-10-31', 18,
       '2026-11-01 00:00:00.000', '2026-11-01 00:00:00.000'
FROM users u WHERE u.external_user_key = 'demo-e9-ph-reyes';

INSERT INTO mydata_bill_payments
 (user_id, institution_id, bill_type, billing_month, due_on, paid_on, amount, ingestion_batch_id)
SELECT u.id, i.id, b.bill_type, m.billing_month,
       DATE_ADD(m.billing_month, INTERVAL b.due_day - 1 DAY),
       DATE_ADD(m.billing_month, INTERVAL b.due_day - 1 DAY),
       b.amount, ib.id
FROM users u
JOIN ingestion_batches ib ON ib.user_id = u.id AND ib.request_key = 'seed-bill-payments'
CROSS JOIN (SELECT 'MOCK_TELECOM' AS institution_code, 'TELECOM' AS bill_type, 21 AS due_day, 45000 AS amount
            UNION ALL SELECT 'MOCK_NHIS', 'HEALTH_INSURANCE', 10, 95000
            UNION ALL SELECT 'MOCK_UTILITY', 'UTILITY', 25, 70000) b
JOIN institutions i ON i.institution_code = b.institution_code
CROSS JOIN (SELECT DATE '2026-05-01' AS billing_month UNION ALL SELECT DATE '2026-06-01'
            UNION ALL SELECT DATE '2026-07-01' UNION ALL SELECT DATE '2026-08-01'
            UNION ALL SELECT DATE '2026-09-01' UNION ALL SELECT DATE '2026-10-01') m
WHERE u.external_user_key = 'demo-e9-ph-reyes';
