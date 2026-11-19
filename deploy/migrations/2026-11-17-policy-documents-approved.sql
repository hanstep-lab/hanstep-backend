-- 이미 떠 있는 운영 DB(볼륨 있음)에서 비자 정책 문서 1~6번을 승인(APPROVED)한 SQL 을 남긴다. 7번은 RETIRED 그대로 둔다.
-- 운영에는 ADMIN 사용자가 없어 승인 API(PATCH /api/v1/admin/policy-documents/{id}/review)를 쓸 수 없어서,
-- 2026-11-17~18 운영 DB에 이 파일과 같은 SQL 을 한 트랜잭션으로 직접 실행했다(운영에는 이미 반영됨).
-- 승인 API(PolicyDocumentReviewService)와 같게 policy_document_reviews 에 REVIEW 이력을 1행씩 먼저 넣고 상태를 바꾼다.
--   시행 기간(effective_from/to)은 기존 값 그대로, superseded_by·reviewed_by 는 NULL.
--   1~6번은 같은 문서(source_type·external_id)의 다른 승인 버전이 없어, API 로 승인했어도 SUPERSEDED 이력은 생기지 않는다.
-- 여러 번 실행해도 결과가 같다. 두 SQL 모두 review_status = 'DRAFT' 인 행만 바꾸고, 한 번 DRAFT 를 벗어난 문서는
--   다시 DRAFT 가 되지 않는다(ReviewStatus.canMoveTo). 이미 승인된 운영 DB 에서는 아무것도 바뀌지 않는다.
-- db/init 05 시드는 그대로 DRAFT 다. 빈 볼륨으로 새로 띄운 DB 에 이 파일을 실행하면 운영과 같게 1~6번이 승인된다.
-- 실행: docker compose -f deploy/compose.prod.yml exec -T mysql sh -c 'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < deploy/migrations/2026-11-17-policy-documents-approved.sql

START TRANSACTION;

-- 1) 승인 이력. 상태를 바꾸기 전에 DRAFT 인 행만 고른다
INSERT INTO policy_document_reviews
  (policy_document_id, reason, from_status, to_status, from_effective_from, from_effective_to,
   to_effective_from, to_effective_to, superseded_by, reviewed_by, note)
SELECT id, 'REVIEW', 'DRAFT', 'APPROVED', effective_from, effective_to, effective_from, effective_to, NULL, NULL,
       '운영 초기 승인: 관리자 계정이 없어 SQL로 반영(2026-11-17, 오지수)'
FROM policy_documents
WHERE id IN (1, 2, 3, 4, 5, 6) AND review_status = 'DRAFT'
ORDER BY id;

-- 2) 상태 변경(시행 기간은 바꾸지 않는다)
UPDATE policy_documents
SET review_status = 'APPROVED'
WHERE id IN (1, 2, 3, 4, 5, 6) AND review_status = 'DRAFT';

COMMIT;

-- MySQL만 바꾼다. Mongo 청크 활성화는 AI reconcile_policy_state.py를 따로 실행한다(Spring 은 MongoDB 청크를 바꾸지 않음).
-- 서버의 hanstep-backend 루트에서, 이 파일을 실행한 뒤 아래 순서로 한다. alias dc='docker compose -f deploy/compose.prod.yml'
--   1) 스냅샷: Spring 상태 목록 API(GET /internal/v1/ai-context/policy-documents)가 모든 행을 스냅샷 파일 모양으로 준다
--      dc exec ai-server python -c "import os, urllib.request as u; r = u.Request(os.environ['SPRING_INTERNAL_API_URL'] + '/internal/v1/ai-context/policy-documents', headers={'X-Internal-Token': os.environ['SPRING_INTERNAL_API_KEY']}); open('/tmp/policy-snapshot.json', 'wb').write(u.urlopen(r, timeout=10).read())"
--   2) 계획만(읽기 전용, 바꿀 것이 있으면 0이 아닌 코드로 끝남):
--      dc exec ai-server python scripts/reconcile_policy_state.py --snapshot /tmp/policy-snapshot.json --complete-snapshot
--   3) 계획을 확인한 뒤 반영:
--      dc exec ai-server python scripts/reconcile_policy_state.py --snapshot /tmp/policy-snapshot.json --complete-snapshot --apply
--   2)를 다시 실행해 바꿀 것이 0건(종료 코드 0)이면 MongoDB 청크가 1~6번 활성, 7번 비활성이다.

-- 확인: 1~6 APPROVED·approved_reviews 1, 7 RETIRED·approved_reviews 0, 시행 기간은 그대로(시드 기준 NULL)
SELECT d.id, d.review_status, d.effective_from, d.effective_to,
       (SELECT COUNT(*) FROM policy_document_reviews r
         WHERE r.policy_document_id = d.id AND r.reason = 'REVIEW'
           AND r.from_status = 'DRAFT' AND r.to_status = 'APPROVED') AS approved_reviews
FROM policy_documents d
WHERE d.id IN (1, 2, 3, 4, 5, 6, 7)
ORDER BY d.id;
