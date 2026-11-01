-- 신한걸음 시드 데이터 v1.1 (hanstep_schema_v1_4.sql 실행 후 실행) — 기준표 데이터만: 거래 카테고리·체류자격·금융기관·E-7-4 점수표(DRAFT)
-- 사람 데이터·Mock 거래는 포함하지 않는다(Mock 생성 스크립트 담당).

-- 거래 카테고리: flow=NEUTRAL 은 소득·지출 집계에서 제외된다.
INSERT INTO transaction_categories (code, parent_code, name_en, name_ko, flow, sort_order) VALUES
 ('INCOME',       NULL,      'Income',              '수입',          'INCOME',  10),
 ('SALARY',       'INCOME',  'Salary',              '급여',          'INCOME',  11),
 ('OTHER_INCOME', 'INCOME',  'Other income',        '기타 수입',     'INCOME',  12),
 ('EXPENSE',      NULL,      'Expense',             '지출',          'EXPENSE', 20),
 ('HOUSING',      'EXPENSE', 'Housing',             '주거비',        'EXPENSE', 21),
 ('UTILITY',      'EXPENSE', 'Utilities',           '공과금',        'EXPENSE', 22),
 ('TELECOM',      'EXPENSE', 'Telecom',             '통신비',        'EXPENSE', 23),
 ('FOOD',         'EXPENSE', 'Food',                '식비',          'EXPENSE', 24),
 ('TRANSPORT',    'EXPENSE', 'Transport',           '교통비',        'EXPENSE', 25),
 ('LIVING',       'EXPENSE', 'Living',              '생활비',        'EXPENSE', 26),
 ('REMITTANCE',   'EXPENSE', 'Remittance home',     '본국 송금',     'EXPENSE', 27),
 ('LOAN_PAYMENT', 'EXPENSE', 'Loan repayment',      '대출 상환',     'EXPENSE', 28),
 ('OTHER',        NULL,      'Uncategorized',       '미분류',        'EXPENSE', 90),
 ('TRANSFER',     NULL,      'Own transfer',        '본인 계좌 이체','NEUTRAL', 91),
 ('SAVINGS',      NULL,      'Savings transfer',    '저축 이체',     'NEUTRAL', 92),
 ('CARD_PAYMENT', NULL,      'Card bill payment',   '카드대금 결제', 'NEUTRAL', 93);

INSERT INTO visa_types (code, name_ko, name_en, is_supported_path) VALUES
 ('E-9',   '비전문취업',   'Non-professional Employment', TRUE),
 ('E-7-4', '숙련기능인력', 'Skilled Worker (Point-based)', TRUE),
 ('H-2',   '방문취업',     'Working Visit', FALSE),
 ('D-2',   '유학',         'Student', FALSE),
 ('F-2',   '거주',         'Residence', FALSE);

INSERT INTO institutions (institution_code, name, name_ko, institution_type) VALUES
 ('SHINHAN_BANK', 'Shinhan Bank (Mock)', '신한은행', 'BANK'),
 ('SHINHAN_CARD', 'Shinhan Card (Mock)', '신한카드', 'CARD'),
 ('MOCK_TELECOM', 'Mock Telecom',        NULL,       'TELECOM');

-- E-7-4 규칙: 10/22 회의록·지수 기능정의서에 옮겨진 농식품부 2026 점수표 값.
-- 법무부 공식 공고로 재확인하기 전까지 DRAFT. DRAFT 규칙은 계산 API가 사용하지 않는다.
INSERT INTO e74_rule_sets (rule_version, effective_from, required_total_score, approval_status, note) VALUES
 ('E74-2026-v1', '2026-01-01', NULL, 'DRAFT', '기준 총점·가점 세부는 법무부 2026 선발계획 확인 후 입력');

INSERT INTO e74_rules (rule_set_id, rule_code, category, condition_json, points, exclusive_group, actor, description) VALUES
 (1, 'INCOME_25_30', 'BASE_INCOME', '{"avgIncome2y":{"gte":25000000,"lt":30000000}}', 50,  'INCOME', 'USER', '최근 2년 평균 연소득 2,500만~3,000만 미만'),
 (1, 'INCOME_30_35', 'BASE_INCOME', '{"avgIncome2y":{"gte":30000000,"lt":35000000}}', 65,  'INCOME', 'USER', '3,000만~3,500만 미만'),
 (1, 'INCOME_35_40', 'BASE_INCOME', '{"avgIncome2y":{"gte":35000000,"lt":40000000}}', 80,  'INCOME', 'USER', '3,500만~4,000만 미만'),
 (1, 'INCOME_40_45', 'BASE_INCOME', '{"avgIncome2y":{"gte":40000000,"lt":45000000}}', 95,  'INCOME', 'USER', '4,000만~4,500만 미만'),
 (1, 'INCOME_45_50', 'BASE_INCOME', '{"avgIncome2y":{"gte":45000000,"lt":50000000}}', 110, 'INCOME', 'USER', '4,500만~5,000만 미만'),
 (1, 'INCOME_50_UP', 'BASE_INCOME', '{"avgIncome2y":{"gte":50000000}}',               120, 'INCOME', 'USER', '5,000만 이상'),
 (1, 'KOREAN_L2',    'BASE_KOREAN', '{"topikLevel":{"eq":2},"or":{"kiipStage":{"eq":2}}}', 50,  'KOREAN', 'USER', 'TOPIK 2급 / KIIP 2단계 등'),
 (1, 'KOREAN_L3',    'BASE_KOREAN', '{"topikLevel":{"eq":3},"or":{"kiipStage":{"eq":3}}}', 80,  'KOREAN', 'USER', 'TOPIK 3급 / KIIP 3단계 등'),
 (1, 'KOREAN_L4_UP', 'BASE_KOREAN', '{"topikLevel":{"gte":4},"or":{"kiipStage":{"gte":4}}}', 120, 'KOREAN', 'USER', 'TOPIK 4급 이상 / KIIP 4단계 이상 등'),
 (1, 'AGE_19_26',    'BASE_AGE',    '{"age":{"gte":19,"lte":26}}', 40, 'AGE', 'USER', '만 19~26세'),
 (1, 'AGE_27_33',    'BASE_AGE',    '{"age":{"gte":27,"lte":33}}', 60, 'AGE', 'USER', '만 27~33세'),
 (1, 'AGE_34_40',    'BASE_AGE',    '{"age":{"gte":34,"lte":40}}', 30, 'AGE', 'USER', '만 34~40세'),
 (1, 'AGE_41_UP',    'BASE_AGE',    '{"age":{"gte":41}}',          10, 'AGE', 'USER', '만 41세 이상'),
 (1, 'REC_MINISTRY', 'BONUS', '{"evidence":"RECOMMENDATION_MINISTRY"}', 30, 'REC_PUBLIC', 'GOVERNMENT', '중앙부처 추천(광역지자체와 중복 제한)'),
 (1, 'REC_PROVINCE', 'BONUS', '{"evidence":"RECOMMENDATION_PROVINCE"}', 30, 'REC_PUBLIC', 'GOVERNMENT', '광역지자체 추천'),
 (1, 'REC_EMPLOYER', 'BONUS', '{"evidence":"RECOMMENDATION_EMPLOYER"}', 50, NULL, 'EMPLOYER', '고용기업 추천'),
 (1, 'TENURE_3Y',    'BONUS', '{"currentEmployerMonths":{"gte":36}}', 20, NULL, 'USER', '현 근무처 3년 이상 근속'),
 (1, 'DEPOP_AREA_3Y','BONUS', '{"depopulationAreaMonths":{"gte":36}}', 20, NULL, 'USER', '인구감소지역·읍면 3년 이상 근무'),
 (1, 'CERT_OR_DEGREE','BONUS','{"evidenceAny":["NATIONAL_CERTIFICATE","KOREAN_DEGREE"]}', 20, NULL, 'USER', '인정 자격증 또는 국내 학위'),
 (1, 'DRIVER_LICENSE','BONUS','{"evidence":"DRIVER_LICENSE"}', 10, NULL, 'USER', '국내 운전면허');
