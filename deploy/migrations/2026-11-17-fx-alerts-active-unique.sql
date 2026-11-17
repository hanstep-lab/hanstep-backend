-- 이미 떠 있는 운영 DB(볼륨 있음)에 fx_alerts 의 ACTIVE 알림 중복 방지(#117)를 반영한다. db/init 은 빈 볼륨에서만 실행되기 때문.
-- 같은 사용자·통화·방향(comparator)의 ACTIVE 알림을 1건만 두도록 active_flag 생성 컬럼과 UNIQUE 를 더한다(동시 생성도 DB가 막는다).
-- 여러 번 실행해도 결과가 같다(이미 있으면 건너뜀). MySQL 에는 ADD COLUMN IF NOT EXISTS 가 없어 information_schema 로 확인한다.
-- 빈 볼륨으로 새로 띄운 DB 에는 실행할 필요가 없다.
-- ACTIVE 중복이 이미 있으면 2)가 실패한다. 목표 환율 알림 API(FX-002) 전에는 fx_alerts 에 쓰는 곳이 없어 운영 테이블은 비어 있다.
-- 실행: docker compose -f deploy/compose.prod.yml exec -T mysql sh -c 'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < deploy/migrations/2026-11-17-fx-alerts-active-unique.sql

-- 1) 01_hanstep_schema.sql 과 같은 정의
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fx_alerts' AND COLUMN_NAME = 'active_flag') = 0,
  'ALTER TABLE fx_alerts ADD COLUMN active_flag TINYINT UNSIGNED AS (CASE WHEN status = ''ACTIVE'' THEN 1 END) VIRTUAL COMMENT ''ACTIVE면 1, 아니면 NULL. uq_fx_alerts_active 로 같은 사용자·통화·방향의 ACTIVE 알림을 1건만 둔다(NULL끼리는 겹쳐도 됨)'' AFTER status',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) 01_hanstep_schema.sql 과 같은 정의
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fx_alerts' AND INDEX_NAME = 'uq_fx_alerts_active') = 0,
  'ALTER TABLE fx_alerts ADD CONSTRAINT uq_fx_alerts_active UNIQUE (user_id, currency, comparator, active_flag)',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 확인: active_flag VIRTUAL GENERATED, uq_fx_alerts_active 의 열 4개(user_id, currency, comparator, active_flag)
SELECT COLUMN_NAME, COLUMN_TYPE, EXTRA, GENERATION_EXPRESSION
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fx_alerts' AND COLUMN_NAME = 'active_flag';
SELECT INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS columns
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fx_alerts' AND INDEX_NAME = 'uq_fx_alerts_active'
GROUP BY INDEX_NAME, NON_UNIQUE;
