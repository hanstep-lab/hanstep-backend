-- 이미 떠 있는 운영 DB(볼륨 있음)에 #85(44cee60)의 Reyes 데모 시드 변경을 반영한다. db/init 은 빈 볼륨에서만 실행되기 때문.
-- 운영 DB는 231257c(#85 전)에서 만들어졌다. 그래서 Reyes 의 아래 두 날짜가 옛 값으로 남아 있다.
--   1) visa_profiles.current_stay_started_on              2022-03-07 → 2022-12-05(입국일)
--   2) employment_histories.started_on('DEMO-PH-METAL')  2022-03-14 → 2022-12-12(입국 뒤 근무 시작)
-- 여러 번 실행해도 결과가 같다(값을 덮어쓰기만 한다). Reyes 가 없으면 아무것도 바꾸지 않는다.
-- 빈 볼륨으로 새로 띄운 DB 에는 실행할 필요가 없다(이미 새 값).
-- 실행: docker compose -f deploy/compose.prod.yml exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < deploy/migrations/2026-11-17-reyes-stay-dates.sql

-- 1) 03_hanstep_demo_seed.sql 과 같은 값
UPDATE visa_profiles vp
JOIN users u ON u.id = vp.user_id
SET vp.current_stay_started_on = '2022-12-05'
WHERE u.external_user_key = 'demo-e9-ph-reyes';

-- 2) 03_hanstep_demo_seed.sql 과 같은 값
UPDATE employment_histories eh
JOIN users u ON u.id = eh.user_id
SET eh.started_on = '2022-12-12'
WHERE u.external_user_key = 'demo-e9-ph-reyes'
  AND eh.employer_name = 'DEMO-PH-METAL';

-- 확인: stay_started 2022-12-05, employment_started 2022-12-12
SELECT (SELECT GROUP_CONCAT(vp.current_stay_started_on) FROM visa_profiles vp JOIN users u ON u.id = vp.user_id
         WHERE u.external_user_key = 'demo-e9-ph-reyes') AS stay_started,
       (SELECT GROUP_CONCAT(eh.started_on) FROM employment_histories eh JOIN users u ON u.id = eh.user_id
         WHERE u.external_user_key = 'demo-e9-ph-reyes' AND eh.employer_name = 'DEMO-PH-METAL') AS employment_started;
