-- 이미 떠 있는 운영 DB(볼륨 있음)에 chat_message_sources.source_id NULL 허용(#102)을 반영한다. db/init 은 빈 볼륨에서만 실행되기 때문.
-- 챗봇 상품 추천 근거(PRODUCT_DOCUMENT)는 숫자 id 가 없어 source_id = NULL, chunk_ref = 상품 문서 ID(예: doc-7b32d0e385aa)로 저장한다.
-- MODIFY 는 같은 정의로 덮어쓰므로 여러 번 실행해도 결과가 같다. 빈 볼륨으로 새로 띄운 DB 에는 실행할 필요가 없다.
-- 실행: docker compose -f deploy/compose.prod.yml exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < deploy/migrations/2026-11-17-chat-source-id-nullable.sql

-- 01_hanstep_schema.sql 과 같은 정의
ALTER TABLE chat_message_sources
  MODIFY source_id BIGINT UNSIGNED NULL COMMENT '근거 행 ID(PRODUCT_DOCUMENT는 NULL)',
  MODIFY chunk_ref VARCHAR(100)    NULL COMMENT 'MongoDB 청크 ID. PRODUCT_DOCUMENT는 상품 문서 ID(조건표 productId)';

-- 확인: source_id YES, chunk_ref YES
SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_TYPE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_message_sources' AND COLUMN_NAME IN ('source_id', 'chunk_ref')
ORDER BY ORDINAL_POSITION;
