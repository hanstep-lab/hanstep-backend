SET NAMES utf8mb4;

-- 신한걸음 데모 시드 (01_hanstep_schema.sql, 02_hanstep_seed.sql 실행 후 실행)
-- 시연용 테스트 사용자 2명과 해외송금 받는 분 1명만 넣음.
--   Nguyen Van Minh(베트남 E-9): 금융 Mock·송금 데모
--   Mark Anthony Reyes(필리핀 E-9): E-7-4 점수 계산 데모(비자 프로필·근무·소득·증빙)
-- 계좌·거래내역 12개월 Mock은 Mock 생성 스크립트가 담당한다(이 파일에 넣지 않음).

-- 데모 사용자: /api/v1/auth/entry 의 entryToken = external_user_key
INSERT INTO users (external_user_key, display_name, preferred_language) VALUES
 ('demo-e9-nguyen', 'NGUYEN VAN MINH', 'en');

-- 해외송금 받는 분: 어머니(베트남, Vietcombank). TR-001 송금 화면·AI-002 재질의에서 사용
-- name_en 은 Mock 거래내역의 counterparty_name 과 같은 표기로 맞춤.
-- relationship: 데모 시나리오에서는 사용자가 이전 대화에서 '어머니'로 이미 확인했다고 가정한다(관계는 추정해 저장하지 않음).
-- swift_code: Vietcombank 공개 SWIFT 코드. 계좌번호는 마스킹 값만 저장함.
INSERT INTO remittance_recipients
 (user_id, external_recipient_key, name_en, relationship, country_code, currency, bank_name, swift_code, account_no_masked, source, is_mock, relationship_confirmed_at)
SELECT u.id, 'mock-recipient-mother-vcb', 'NGUYEN THI MAI', 'MOTHER', 'VN', 'VND', 'Vietcombank', 'BFTVVNVX', '****5566', 'MOCK', TRUE, '2026-11-01 00:00:00.000'
FROM users u WHERE u.external_user_key = 'demo-e9-nguyen';

-- E-7-4 점수 계산 데모 사용자(필리핀 E-9 제조업, 가상 인물·합성 데이터). 고용기업 추천서만 아직 내지 않았다.
-- 기대 결과(기준일 2026-11-12, E74-2026-v1): NEEDS_EVIDENCE, 확정 235점, 가능 285점(증빙 필요 REC_EMPLOYER·REQ_EMPLOYER_REC).
-- 감점·제외 6문항은 저장하지 않는다(계산 요청 때 legalDeclaration 으로 보낸다).
INSERT INTO users (external_user_key, display_name, preferred_language) VALUES
 ('demo-e9-ph-reyes', 'MARK ANTHONY REYES', 'en');

INSERT INTO visa_profiles
 (user_id, visa_type_id, stay_expires_on, nationality_code, birth_date, current_stay_started_on, prior_stay_months, evidence_status)
SELECT u.id, vt.id, '2027-01-06', 'PHL', '1996-07-21', '2022-03-07', 0, 'VERIFIED'
FROM users u JOIN visa_types vt ON vt.code = 'E-9' WHERE u.external_user_key = 'demo-e9-ph-reyes';

INSERT INTO employment_histories
 (user_id, employer_name, industry_code, workplace_region_code, is_depopulation_area, started_on, contract_end_on, contract_annual_salary, verification_status)
SELECT u.id, 'DEMO-PH-METAL', 'MANUFACTURING', '48250', FALSE, '2022-03-14', '2029-03-13', 31500000, 'VERIFIED'
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
