-- 이미 떠 있는 운영 DB(볼륨 있음)에 2026-11-17 develop 의 db/init 변경을 반영한다. db/init 은 빈 볼륨에서만 실행되기 때문.
-- 기준: 운영 DB는 231257c(#85 전)에서 만들어졌다. 그 뒤 바뀐 것 중 이 파일은 아래 4가지를 반영한다.
--   #85(44cee60)의 Reyes 체류 시작일·근무 시작일은 2026-11-17-reyes-stay-dates.sql 에서 반영한다.
--   1) mydata_bill_payments 테이블(#87)  2) 청구기관 MOCK_NHIS·MOCK_UTILITY(#87)
--   3) Reyes 청구 납부 내역 18행(#87, 03_hanstep_demo_seed.sql)  4) E74-2026-v1 승인(#95)
-- 여러 번 실행해도 결과가 같다(이미 있으면 건너뜀). 빈 볼륨으로 새로 띄운 DB 에는 실행할 필요가 없다.
-- 실행: docker compose -f deploy/compose.prod.yml exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < deploy/migrations/2026-11-17-bill-payments-e74-approved.sql

-- 1) 01_hanstep_schema.sql 과 같은 정의
-- 통신비·건강보험료·공과금 청구·납부 내역 저장용 테이블 (대안신용 모의 사전진단의 연체 횟수 입력) --
CREATE TABLE IF NOT EXISTS mydata_bill_payments (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  user_id             BIGINT UNSIGNED NOT NULL COMMENT '사용자',
  institution_id      BIGINT UNSIGNED NOT NULL COMMENT '청구기관(통신사·건강보험공단·공과금 기관)',
  bill_type           ENUM('TELECOM','HEALTH_INSURANCE','UTILITY') NOT NULL COMMENT '통신비/건강보험료/공과금',
  billing_month       DATE            NOT NULL COMMENT '청구 월(그 달 1일)',
  due_on              DATE            NOT NULL COMMENT '납부 기한',
  paid_on             DATE            NULL COMMENT '납부일(NULL=미납). 연체 = 납부일이 기한보다 늦음 또는 기한이 지난 미납(임시 기준)',
  amount              DECIMAL(18,2)   NOT NULL COMMENT '청구 금액(KRW)',
  ingestion_batch_id  BIGINT UNSIGNED NOT NULL COMMENT '적재 배치(Mock 여부·출처)',
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '적재',
  updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정',
  PRIMARY KEY (id),
  CONSTRAINT uq_mydata_bill_payments UNIQUE (user_id, institution_id, bill_type, billing_month),
  KEY ix_mydata_bill_payments_user (user_id, bill_type, billing_month),
  CONSTRAINT fk_mydata_bill_payments_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_mydata_bill_payments_inst FOREIGN KEY (institution_id) REFERENCES institutions (id),
  CONSTRAINT fk_mydata_bill_payments_batch FOREIGN KEY (ingestion_batch_id) REFERENCES ingestion_batches (id) ON DELETE CASCADE,
  CONSTRAINT ck_mydata_bill_payments_month CHECK (DAYOFMONTH(billing_month) = 1),
  CONSTRAINT ck_mydata_bill_payments_amount CHECK (amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P1] Mock 청구 납부 내역(통신·건보·공과금 연체 집계)';

-- 2) 02_hanstep_seed.sql 과 같은 값
INSERT IGNORE INTO institutions (institution_code, name, name_ko, institution_type) VALUES
 ('MOCK_NHIS',    'Mock National Health Insurance', NULL, 'PUBLIC'),
 ('MOCK_UTILITY', 'Mock Utility (City Gas)',        NULL, 'BILLER');

-- 3) 03_hanstep_demo_seed.sql 과 같은 값(Reyes 가 없으면 아무것도 넣지 않는다)
INSERT IGNORE INTO ingestion_batches
 (user_id, source_type, is_mock, request_key, status, coverage_from, coverage_to, record_count, started_at, finished_at)
SELECT u.id, 'MOCK', TRUE, 'seed-bill-payments', 'SUCCEEDED', '2026-05-01', '2026-10-31', 18,
       '2026-11-01 00:00:00.000', '2026-11-01 00:00:00.000'
FROM users u WHERE u.external_user_key = 'demo-e9-ph-reyes';

INSERT IGNORE INTO mydata_bill_payments
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

-- 4) 02_hanstep_seed.sql 과 같은 값
UPDATE e74_rule_sets
SET required_total_score = 200,
    approval_status = 'APPROVED',
    note = '2026 선발계획 개선본(2026-06-01) 점수표 대조 완료(2026-11-17)'
WHERE rule_version = 'E74-2026-v1';

-- 확인: bill_rows 18, nhis_utility 2, e74 APPROVED/200
SELECT (SELECT COUNT(*) FROM mydata_bill_payments bp JOIN users u ON u.id = bp.user_id
         WHERE u.external_user_key = 'demo-e9-ph-reyes') AS bill_rows,
       (SELECT COUNT(*) FROM institutions WHERE institution_code IN ('MOCK_NHIS', 'MOCK_UTILITY')) AS nhis_utility,
       (SELECT CONCAT(approval_status, '/', required_total_score) FROM e74_rule_sets
         WHERE rule_version = 'E74-2026-v1') AS e74;
