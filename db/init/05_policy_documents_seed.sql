SET NAMES utf8mb4;

-- 신한걸음 비자 정책 원천 문서 시드 (hanstep-AI docs/policy_documents_seed.sql, 2026-11-09 AI 제안 그대로)
-- 01_hanstep_schema.sql 실행 후 실행. 같은 SQL 을 두 번 돌리면 content_hash UNIQUE 로 실패한다(정상).
-- policy_documents seed for hanstep-AI RAG (2026-11-09, AI 지수 작성 · BE 검토용 제안)
-- policy_version 값은 제안. BE 규칙이 있으면 바꾸고, 바꾼 값은 아래 export 로 돌려주세요.
-- effective_from 은 공식 원문에 시행일이 명시되지 않아 NULL. AI-R1 searchable 은 NULL 이면 시행 시작 조건을 건너뛴다(APPROVED 면 검색 가능).
-- chunk_count·embedding_model·chroma_collection·ingested_at 은 AI 적재 후 INT-13 으로 채워짐.
-- review_status: 관리자 검토 전 DRAFT. 선발계획 원본(06/01 개선본으로 대체됨)은 RETIRED.

INSERT INTO policy_documents
  (source_type, title, issuing_agency, source_url, external_id, visa_type_code, published_on,
   effective_from, effective_to, policy_version, content_hash, object_key, review_status)
VALUES
  ('GUIDE', 'K-point E74 설명자료(중앙부처용)', '법무부', 'https://www.mafra.go.kr/home/5108/subview.do?enc=Zm5jdDF8QEB8JTJGYmJzJTJGaG9tZSUyRjc5MSUyRjU3NzIxNSUyRmFydGNsVmlldy5kbyUzRg%3D%3D', NULL, 'E-7-4', NULL, NULL, NULL, 'E74-CENTRAL-GUIDE-2026-v1', 'e38dee718554ae2fe1c24f6388a9eec373058ca4ddd6b9651b80e089fec963ac', 'policy-sources/visa/sha256/e38dee718554ae2fe1c24f6388a9eec373058ca4ddd6b9651b80e089fec963ac.hwp', 'DRAFT'),  -- visa/guides/e74_central_agency_guide_2026.hwp
  ('GUIDE', 'K-point E74 숙련기능인력 혁신적 확대 방안 — 하이코리아 전자민원 신청 방법', '법무부', 'https://hrdc.hrdkorea.or.kr/hrdc/197116', NULL, 'E-7-4', NULL, NULL, NULL, 'E74-ONLINE-MANUAL-2026-v1', '9f6bd0ec911cdc556e579c023227f20a495f13b7189af1f7d126d9c4da254444', 'policy-sources/visa/sha256/9f6bd0ec911cdc556e579c023227f20a495f13b7189af1f7d126d9c4da254444.pdf', 'DRAFT'),  -- visa/guides/e74_online_application_manual_2026.pdf
  ('GUIDE', '’26년 숙련기능인력 전환 추천서 발급 신청 안내', '고용노동부', 'https://www.moel.go.kr/local/pyeongtaek/news/notice/noticeView.do?bbs_seq=20260300430', NULL, 'E-7-4', '2026-03-10', NULL, NULL, 'E74-MOEL-RECOMMEND-2026-v1', '1ecc901473109ed7c52f4f90e34d31f4119a09f06272547bb09713c07e7ecad1', 'policy-sources/visa/sha256/1ecc901473109ed7c52f4f90e34d31f4119a09f06272547bb09713c07e7ecad1.hwp', 'DRAFT'),  -- visa/recommendations/central/moel/e74_moel_2026.hwp
  ('NOTICE', '2026년 농축산분야 숙련기능인력(E-7-4) 고용추천 운영방안', '농림축산식품부', 'https://www.mafra.go.kr/home/5108/subview.do?enc=Zm5jdDF8QEB8JTJGYmJzJTJGaG9tZSUyRjc5MSUyRjU3NzIxNSUyRmFydGNsVmlldy5kbyUzRg%3D%3D', '농림축산식품부 공고 제2026-163호', 'E-7-4', '2026-03-05', NULL, NULL, 'E74-MAFRA-2026-163', '1c2fa2951038ff3ef1efb91fd8fcba4690ff0bb51429e5d18b7813f9b91e5021', 'policy-sources/visa/sha256/1c2fa2951038ff3ef1efb91fd8fcba4690ff0bb51429e5d18b7813f9b91e5021.pdf', 'DRAFT'),  -- visa/recommendations/central/mafra/e74_mafra_20260305.pdf
  ('NOTICE', '2026년 외국인 숙련기능인력(E-7-4) 경기도 추천계획 공고', '경기도', 'https://www.gg.go.kr/bbs/boardView.do?bIdx=242674116&bcIdx=602&bsIdx=469&menuId=1547&page=1', '경기도 공고 제2026-5708호(수정)', 'E-7-4', '2026-06-04', NULL, NULL, 'E74-GG-2026-5708-rev1', '6657588aa0d37e744fc7997f2a7c0be420d20b27fcc70de9086e86f4c1a95a1a', 'policy-sources/visa/sha256/6657588aa0d37e744fc7997f2a7c0be420d20b27fcc70de9086e86f4c1a95a1a.hwpx', 'DRAFT'),  -- visa/recommendations/local/gyeonggi/e74_gyeonggi_20260601.hwpx
  ('NOTICE', '2026년 숙련기능인력(E-7-4) 선발 계획', '법무부', 'https://hrdc.hrdkorea.or.kr/hrdc/197116', NULL, 'E-7-4', '2026-06-01', NULL, NULL, 'E74-SELECTION-2026-20260601', '5c6d0d26537ed48e815db5969294cc453fc10819af2ab6f887fc2c454676ca78', 'policy-sources/visa/sha256/5c6d0d26537ed48e815db5969294cc453fc10819af2ab6f887fc2c454676ca78.pdf', 'DRAFT'),  -- visa/selection/e74_selection_20260601.pdf
  ('NOTICE', '2026년 숙련기능인력(E-7-4) 선발 계획', '법무부', 'https://www.icff.or.kr/board/notice.php?code=notice&idx=15153&page=1&ptype=view', NULL, 'E-7-4', NULL, NULL, NULL, 'E74-SELECTION-2026-original', '4c9c48a513fc34659ee3f4168bbf8dfe89d8f2c39e50d5c3840490f254945b69', 'policy-sources/visa/sha256/4c9c48a513fc34659ee3f4168bbf8dfe89d8f2c39e50d5c3840490f254945b69.hwp', 'RETIRED');  -- visa/selection/e74_selection_2026_original.hwp

-- 등록 후 AI 쪽(fill_manifest_ids.py)에 돌려줄 값:
-- SELECT id, policy_version, content_hash FROM policy_documents ORDER BY id;
