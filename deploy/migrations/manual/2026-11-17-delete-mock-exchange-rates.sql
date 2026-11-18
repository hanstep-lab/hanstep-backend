-- 운영 DB 의 Mock 환율(04 시드, is_mock = TRUE)을 지운다(#119). 환율은 실데이터만 둔다.
-- ⚠️ 자동 배포는 이 파일을 실행하지 않는다(auto-deploy.sh 는 deploy/migrations 바로 아래 *.sql 만 실행). 사람이 직접 실행한다.
-- 실행 시점: 환율 수집기를 배포한 직후, 백필 바로 전. 이 SQL 과 백필을 연달아 실행한다.
--   그 사이(보통 몇 분) 화면 송금 견적·챗봇 환율·목표 환율 알림 만들기는 FX_RATE_NOT_FOUND 다.
-- 지우는 행: is_mock = TRUE 만. 수집기가 넣은 실데이터(is_mock = FALSE)는 건드리지 않는다.
-- 여러 번 실행해도 결과가 같다(두 번째부터 지울 행 0).
-- 실행: docker compose -f deploy/compose.prod.yml exec -T mysql sh -c 'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < deploy/migrations/manual/2026-11-17-delete-mock-exchange-rates.sql

-- 1) 실행 전 확인: 지울 행 수. 11/17 운영 확인값은 is_mock = 1 인 USD(KOREAEXIM)·PHP(MOCK)·VND(MARKET_REF) 각 246, 합계 738
SELECT source, is_mock, currency, COUNT(*) AS cnt, MIN(rate_date) AS first_date, MAX(rate_date) AS last_date
FROM exchange_rates
GROUP BY source, is_mock, currency
ORDER BY is_mock DESC, source, currency;

-- 2) 실행 전 확인: Mock 행을 가리키는 알림(fx_alerts.triggered_rate_id). 0 이어야 한다.
--    0 이 아니면 외래 키(fk_fx_alerts_rate) 때문에 3)이 오류로 멈추고 아무것도 지워지지 않는다. 그 알림을 먼저 확인한다
SELECT COUNT(*) AS alerts_on_mock_rates
FROM fx_alerts a
JOIN exchange_rates e ON e.id = a.triggered_rate_id
WHERE e.is_mock = TRUE;

-- 3) 삭제
DELETE FROM exchange_rates WHERE is_mock = TRUE;
SELECT ROW_COUNT() AS deleted_rows;

-- 4) 실행 후 확인: mock_rows_left 0. 남은 행은 실데이터만(백필 전이면 0행)
SELECT COUNT(*) AS mock_rows_left FROM exchange_rates WHERE is_mock = TRUE;
SELECT source, is_mock, currency, COUNT(*) AS cnt, MIN(rate_date) AS first_date, MAX(rate_date) AS last_date
FROM exchange_rates
GROUP BY source, is_mock, currency
ORDER BY source, currency;
