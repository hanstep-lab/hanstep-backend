SET NAMES utf8mb4;

-- 신한걸음 데모 시드 (01_hanstep_schema.sql, 02_hanstep_seed.sql 실행 후 실행)
-- 시연용 테스트 사용자 1명(Nguyen Van Minh, E-9)과 해외송금 받는 분 1명만 넣음.
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
