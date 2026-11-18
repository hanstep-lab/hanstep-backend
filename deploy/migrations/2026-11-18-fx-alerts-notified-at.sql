-- 이미 떠 있는 운영 DB(볼륨 있음)에 fx_alerts.notified_at(#129)을 반영한다. db/init 은 빈 볼륨에서만 실행되기 때문.
-- 목표 환율 도달을 사용자에게 알린 시각이다. 앱이 SSE(GET /api/v1/fx/alerts/stream)로 도달을 받아 로컬 알림을 띄운 뒤
-- POST /api/v1/fx/alerts/{alertId}/notified 를 보내야 채워진다. 기존 행은 NULL(아직 안 알림)로 남는다.
-- 여러 번 실행해도 결과가 같다(이미 있으면 건너뜀). MySQL 에는 ADD COLUMN IF NOT EXISTS 가 없어 information_schema 로 확인한다.
-- 빈 볼륨으로 새로 띄운 DB 에는 실행할 필요가 없다.
-- 실행: docker compose -f deploy/compose.prod.yml exec -T mysql sh -c 'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < deploy/migrations/2026-11-18-fx-alerts-notified-at.sql

-- 01_hanstep_schema.sql 과 같은 정의
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fx_alerts' AND COLUMN_NAME = 'notified_at') = 0,
  'ALTER TABLE fx_alerts ADD COLUMN notified_at DATETIME(3) NULL COMMENT ''사용자에게 도달을 알린 시각(앱이 로컬 알림을 띄운 뒤 보낸 POST notified. SSE 로 보낸 것만으로는 채우지 않음)'' AFTER triggered_at',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 확인: notified_at datetime(3) YES
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fx_alerts' AND COLUMN_NAME = 'notified_at';
