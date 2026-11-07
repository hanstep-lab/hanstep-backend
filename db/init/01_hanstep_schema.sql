SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- A. 사용자 · 동의 · 온보딩
-- ---------------------------------------------------------------------

-- 사용자 계정 저장용 테이블 --
CREATE TABLE users (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '사용자 ID',
  external_user_key   VARCHAR(64)     NOT NULL COMMENT 'SOL Global 고객 식별값 대체(데모 진입 키). 실서비스는 SOL 인증 토큰 검증 후 매핑',
  display_name        VARCHAR(100)    NOT NULL COMMENT '표시 이름(영문, SOL 에서 전달받는 값 가정)',
  preferred_language  ENUM('en','ko','vi','zh') NOT NULL DEFAULT 'en' COMMENT '서비스 언어(FR-003, ON-001 선택지 4종, 시연은 en)',
  role                ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER' COMMENT '권한',
  status              ENUM('ACTIVE','SUSPENDED','WITHDRAWN') NOT NULL DEFAULT 'ACTIVE' COMMENT '계정 상태',
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정',
  PRIMARY KEY (id),
  CONSTRAINT uq_users_external_key UNIQUE (external_user_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 사용자 계정(앱인앱: 자체 회원가입·비밀번호 없음)';

-- 약관·마이데이터 동의 이력 저장용 테이블 --
CREATE TABLE user_consents (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '동의 ID',
  user_id         BIGINT UNSIGNED NOT NULL COMMENT '사용자',
  consent_type    ENUM('TERMS','PRIVACY','ID_DOCUMENT','MYDATA','MYDATA_TERMS','AI_CHAT','CREDIT_EVALUATION','MARKETING') NOT NULL COMMENT '동의 종류(MYDATA=개인신용정보 전송요구·수집이용, MYDATA_TERMS=마이데이터 서비스 이용약관, CREDIT_EVALUATION=금융 진단(모의) 활용·선택)',
  policy_version  VARCHAR(40)     NOT NULL COMMENT '약관 버전',
  status          ENUM('GRANTED','REVOKED') NOT NULL COMMENT '동의 상태',
  granted_at      DATETIME(3)     NOT NULL COMMENT '동의 시각',
  revoked_at      DATETIME(3)     NULL COMMENT '철회 시각',
  expires_at      DATETIME(3)     NULL COMMENT '만료 시각',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  PRIMARY KEY (id),
  KEY ix_user_consents_user_type (user_id, consent_type, granted_at),
  CONSTRAINT fk_user_consents_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT ck_user_consents_status CHECK ((status = 'GRANTED' AND revoked_at IS NULL) OR (status = 'REVOKED' AND revoked_at IS NOT NULL)),
  CONSTRAINT ck_user_consents_expiry CHECK (expires_at IS NULL OR expires_at > granted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 동의 이력(append-only, 철회는 새 행 또는 status 갱신)';

-- 온보딩 설문 응답(거주 목적 등) 저장용 테이블 --
CREATE TABLE onboarding_profiles (
  id                       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '온보딩 프로필 ID',
  user_id                  BIGINT UNSIGNED NOT NULL COMMENT '사용자',
  residence_purpose        ENUM('WORK','STUDY','FAMILY','OTHER') NULL COMMENT '거주 목적',
  declared_monthly_income  DECIMAL(18,2)   NULL COMMENT '자기신고 월소득(KRW)',
  korea_entry_date         DATE            NULL COMMENT '최초 입국일(국내 거주기간 산정)',
  residence_region_code    VARCHAR(20)     NULL COMMENT '거주 시도 코드',
  housing_provided         BOOLEAN         NULL COMMENT '사업주 숙소 제공 여부(1027 회의)',
  created_at               DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  updated_at               DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정',
  PRIMARY KEY (id),
  CONSTRAINT uq_onboarding_profiles_user UNIQUE (user_id),
  CONSTRAINT fk_onboarding_profiles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT ck_onboarding_profiles_income CHECK (declared_monthly_income IS NULL OR declared_monthly_income >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 온보딩 설문 응답(체류정보는 OCR로 받으므로 제외)';

-- 온보딩 단계별 진행 상태 저장용 테이블 --
CREATE TABLE onboarding_progress (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  user_id       BIGINT UNSIGNED NOT NULL COMMENT '사용자',
  step_code     ENUM('LANGUAGE','ID_DOCUMENT','MYDATA_CONSENT','GOALS','GOAL_DETAILS','SUMMARY_CONFIRM') NOT NULL COMMENT '온보딩 단계(1027 플로우 + ON-006 최종 확인)',
  status        ENUM('NOT_STARTED','IN_PROGRESS','COMPLETED','SKIPPED') NOT NULL DEFAULT 'NOT_STARTED' COMMENT '단계 상태',
  completed_at  DATETIME(3)     NULL COMMENT '완료 시각',
  updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정',
  PRIMARY KEY (id),
  CONSTRAINT uq_onboarding_progress_step UNIQUE (user_id, step_code),
  CONSTRAINT fk_onboarding_progress_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT ck_onboarding_progress_done CHECK (status <> 'COMPLETED' OR completed_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 온보딩 단계별 진행(FR-006)';

-- 금융 목표(목돈·송금·생활비 등) 저장용 테이블 --
CREATE TABLE financial_goals (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '목표 ID',
  user_id        BIGINT UNSIGNED NOT NULL COMMENT '사용자',
  goal_type      ENUM('LUMP_SUM','REMITTANCE','LIVING_COST','CREDIT','SETTLEMENT') NOT NULL COMMENT '목돈/송금/생활비/신용/장기정착(복수 선택)',
  target_amount  DECIMAL(18,2)   NULL COMMENT '목표 금액(목돈 마련 추가질의)',
  currency       CHAR(3)         NOT NULL DEFAULT 'KRW' COMMENT '통화',
  target_date    DATE            NULL COMMENT '목표 시점',
  status         ENUM('ACTIVE','ACHIEVED','CANCELLED') NOT NULL DEFAULT 'ACTIVE' COMMENT '상태',
  created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정',
  PRIMARY KEY (id),
  KEY ix_financial_goals_user (user_id, status),
  CONSTRAINT fk_financial_goals_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT ck_financial_goals_amount CHECK (target_amount IS NULL OR target_amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 금융 목표(온보딩 4단계)';

-- 매달 꼭 나가는 돈(송금·월세·공과금) 저장용 테이블 --
CREATE TABLE monthly_commitments (
  id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '필수 확보액 ID',
  user_id                BIGINT UNSIGNED NOT NULL COMMENT '사용자',
  commitment_type        ENUM('REMITTANCE','RENT','UTILITY','TELECOM','MANDATORY_SAVING','LIVING_MINIMUM','VARIABLE_EXPENSE','OTHER') NOT NULL COMMENT '항목 유형(타입별 다중 행). VARIABLE_EXPENSE=사용자가 입력한 예상 변동지출(식비·교통·여가 등, 여유자금 계산 시 차감)',
  label                  VARCHAR(100)    NULL COMMENT '사용자 표시명(예: 월세)',
  amount                 DECIMAL(18,2)   NOT NULL COMMENT '월 금액',
  currency               CHAR(3)         NOT NULL DEFAULT 'KRW' COMMENT '통화',
  due_day                TINYINT UNSIGNED NULL COMMENT '매월 납부일(1~31)',
  source                 ENUM('USER','DETECTED_CONFIRMED') NOT NULL DEFAULT 'USER' COMMENT '직접 입력 / 거래에서 찾아 사용자가 확인',
  detected_from_txn_id   BIGINT UNSIGNED NULL COMMENT '근거 거래(본국 송금 top3 확인 등)',
  is_active              BOOLEAN         NOT NULL DEFAULT TRUE COMMENT '활성 여부',
  created_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  updated_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정',
  PRIMARY KEY (id),
  KEY ix_monthly_commitments_user (user_id, is_active, commitment_type),
  CONSTRAINT fk_monthly_commitments_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT ck_monthly_commitments_amount CHECK (amount >= 0),
  CONSTRAINT ck_monthly_commitments_due CHECK (due_day IS NULL OR due_day BETWEEN 1 AND 31)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 매달 반드시 확보할 금액. MANDATORY_SAVING은 가용자금에서 차감하지 않음';

-- ---------------------------------------------------------------------
-- B. 문서 · OCR
-- ---------------------------------------------------------------------

-- 업로드 문서(외국인등록증) 정보·OCR 원본 결과 저장용 테이블 --
CREATE TABLE documents (
  id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '문서 ID',
  user_id              BIGINT UNSIGNED NOT NULL COMMENT '소유자',
  document_type        ENUM('ARC','DRIVER_LICENSE','CERTIFICATE','INSURANCE_PDF','OTHER') NOT NULL COMMENT 'ARC=외국인등록증',
  side                 ENUM('FRONT','BACK','SINGLE') NOT NULL DEFAULT 'SINGLE' COMMENT '면',
  object_key           VARCHAR(512)    NULL COMMENT 'R2 hanstep-arc-private 버킷 키(원본 삭제 후 NULL)',
  content_type         VARCHAR(100)    NOT NULL COMMENT 'MIME',
  file_size_bytes      INT UNSIGNED    NOT NULL COMMENT '파일 크기',
  sha256               CHAR(64)        NOT NULL COMMENT '중복 업로드 판별',
  status               ENUM('UPLOADED','OCR_QUEUED','OCR_PROCESSING','PENDING_CONFIRMATION','CONFIRMED','FAILED') NOT NULL DEFAULT 'UPLOADED' COMMENT '처리 상태',
  ocr_model_version    VARCHAR(60)     NULL COMMENT '예: ppocrv5-ko-mobile-int8',
  ocr_latency_ms       INT UNSIGNED    NULL COMMENT 'OCR 처리시간(성능 지표)',
  failure_code         VARCHAR(50)     NULL COMMENT 'OCR_FAILED, LOW_QUALITY 등',
  original_deleted_at  DATETIME(3)     NULL COMMENT '원본 이미지 삭제 시각(추론 후 삭제 원칙)',
  retention_until      DATETIME(3)     NOT NULL COMMENT '원본 최대 보관 기한',
  confirmed_at         DATETIME(3)     NULL COMMENT '사용자 확인 시각',
  created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '업로드',
  updated_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정',
  PRIMARY KEY (id),
  CONSTRAINT uq_documents_user_hash UNIQUE (user_id, sha256),
  KEY ix_documents_user_type (user_id, document_type, created_at),
  CONSTRAINT fk_documents_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT ck_documents_confirmed CHECK (status <> 'CONFIRMED' OR confirmed_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 업로드 문서 메타(OCR 원 응답 JSON은 저장하지 않음, 필드값은 document_fields)';

-- OCR 항목별 추출값·사용자 확정값 저장용 테이블 --
CREATE TABLE document_fields (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  document_id        BIGINT UNSIGNED NOT NULL COMMENT '문서',
  field_key          VARCHAR(50)     NOT NULL COMMENT 'registrationNo, nameEn, nationality, visaType, issueDate, birthDate, stayExpiry, address 등',
  extracted_value    VARCHAR(255)    NULL COMMENT 'OCR 추출값(확인 전 임시 보관, 문서 확정 시 NULL로 삭제)',
  confidence         DECIMAL(5,4)    NULL COMMENT '신뢰도 0~1',
  is_masked          BOOLEAN         NOT NULL DEFAULT FALSE COMMENT '마스킹 저장 여부(등록번호=TRUE)',
  confirmed_value    VARCHAR(255)    NULL COMMENT '사용자 확인·수정값(visa_profiles 반영 후 NULL로 삭제)',
  status             ENUM('EXTRACTED','MISSING','CONFIRMED','CORRECTED') NOT NULL DEFAULT 'EXTRACTED' COMMENT '수정 전후 구분(FR-021)',
  confirmed_at       DATETIME(3)     NULL COMMENT '확인 시각',
  PRIMARY KEY (id),
  CONSTRAINT uq_document_fields_key UNIQUE (document_id, field_key),
  CONSTRAINT fk_document_fields_document FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE,
  CONSTRAINT ck_document_fields_conf CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] OCR 필드별 추출값·확인값(확인 전 임시). 확정 후 값은 지우고 status·confidence만 남김(OCR 정확도 지표용)';

-- ---------------------------------------------------------------------
-- C. 체류 · 비자 프로필
-- ---------------------------------------------------------------------

-- 체류자격 목록(E-9, E-7-4 등) 저장용 테이블 --
CREATE TABLE visa_types (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  code               VARCHAR(20)     NOT NULL COMMENT 'E-9, E-7-4 등',
  name_ko            VARCHAR(100)    NOT NULL COMMENT '한글명',
  name_en            VARCHAR(100)    NOT NULL COMMENT '영문명',
  is_supported_path  BOOLEAN         NOT NULL DEFAULT FALSE COMMENT 'MVP 지원 경로 여부(E-9→E-7-4)',
  PRIMARY KEY (id),
  CONSTRAINT uq_visa_types_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 체류자격 사전(심사 규칙은 e74_rules)';

-- 사용자 현재 체류 정보(체류자격·만료일) 저장용 테이블 --
CREATE TABLE visa_profiles (
  id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '비자 프로필 ID',
  user_id                 BIGINT UNSIGNED NOT NULL COMMENT '사용자',
  visa_type_id            BIGINT UNSIGNED NULL COMMENT '현재 체류자격',
  stay_expires_on         DATE            NULL COMMENT '체류 만료일',
  nationality_code        CHAR(3)         NULL COMMENT 'ISO 3166-1 alpha-3',
  birth_date              DATE            NULL COMMENT 'E-7-4 나이 점수용(나이는 저장하지 않고 계산)',
  source_document_id      BIGINT UNSIGNED NULL COMMENT '근거 OCR 문서',
  evidence_status         ENUM('DECLARED','NEEDS_VERIFICATION','PARTIALLY_VERIFIED','VERIFIED') NOT NULL DEFAULT 'DECLARED' COMMENT '증빙 상태',
  created_at              DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  updated_at              DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정',
  PRIMARY KEY (id),
  CONSTRAINT uq_visa_profiles_user UNIQUE (user_id),
  CONSTRAINT fk_visa_profiles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_visa_profiles_type FOREIGN KEY (visa_type_id) REFERENCES visa_types (id),
  CONSTRAINT fk_visa_profiles_document FOREIGN KEY (source_document_id) REFERENCES documents (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 현재 체류 프로필(OCR 확정값의 유일한 보관처, 등록번호는 저장하지 않음)';

-- 근무 이력(사업장·근속 기간) 저장용 테이블 --
CREATE TABLE employment_histories (
  id                       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  user_id                  BIGINT UNSIGNED NOT NULL COMMENT '사용자',
  employer_name            VARCHAR(200)    NOT NULL COMMENT '사업장명',
  industry_code            VARCHAR(30)     NOT NULL COMMENT '업종(MANUFACTURING, AGRICULTURE, FISHERY 등 — E-7-4 업종 특례)',
  workplace_region_code    VARCHAR(20)     NULL COMMENT '사업장 소재 시군구 코드',
  is_depopulation_area     BOOLEAN         NULL COMMENT '인구감소지역·읍면 여부(가점)',
  started_on               DATE            NOT NULL COMMENT '근무 시작일',
  ended_on                 DATE            NULL COMMENT '근무 종료일(현직=NULL)',
  contract_end_on          DATE            NULL COMMENT '현 고용계약 만료일',
  contract_annual_salary   DECIMAL(18,2)   NULL COMMENT '고용계약 연봉',
  verification_status      ENUM('DECLARED','NEEDS_EVIDENCE','VERIFIED','REJECTED') NOT NULL DEFAULT 'DECLARED' COMMENT '증빙 상태',
  evidence_document_id     BIGINT UNSIGNED NULL COMMENT '경력증명서 등',
  created_at               DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  updated_at               DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정',
  PRIMARY KEY (id),
  KEY ix_employment_histories_user (user_id, started_on),
  CONSTRAINT fk_employment_histories_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_employment_histories_doc FOREIGN KEY (evidence_document_id) REFERENCES documents (id) ON DELETE SET NULL,
  CONSTRAINT ck_employment_histories_period CHECK (ended_on IS NULL OR ended_on >= started_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 근무 이력(근속·지역 가점, 근무처 변경 이력)';

-- 비자 점수용 증빙(TOPIK·자격증·추천서) 저장용 테이블 --
CREATE TABLE visa_evidence (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  user_id         BIGINT UNSIGNED NOT NULL COMMENT '사용자',
  evidence_type   ENUM('TOPIK','KIIP','KIIP_PRE_TEST','DRIVER_LICENSE','NATIONAL_CERTIFICATE','KOREAN_DEGREE','RECOMMENDATION_EMPLOYER','RECOMMENDATION_MINISTRY','RECOMMENDATION_PROVINCE') NOT NULL COMMENT '증빙 종류(감점 사유는 저장하지 않음)',
  level_value     VARCHAR(30)     NULL COMMENT 'TOPIK 급수, KIIP 단계, 자격명 등',
  issued_on       DATE            NULL COMMENT '취득·발급일',
  expires_on      DATE            NULL COMMENT '유효기간(TOPIK 2년 등)',
  document_id     BIGINT UNSIGNED NULL COMMENT '증빙 문서',
  status          ENUM('DECLARED','NEEDS_EVIDENCE','VERIFIED','REJECTED') NOT NULL DEFAULT 'DECLARED' COMMENT '사용자 신고/증빙 필요/확인',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정',
  PRIMARY KEY (id),
  KEY ix_visa_evidence_user (user_id, evidence_type),
  CONSTRAINT fk_visa_evidence_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_visa_evidence_doc FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 한국어·자격·추천 증빙(FR-025)';

-- 연도별 공식 인정소득(소득금액증명원) 저장용 테이블 (후순위 가능: 1차 개발은 마이데이터 추정 소득 사용) --
CREATE TABLE income_records (
  id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  user_id              BIGINT UNSIGNED NOT NULL COMMENT '사용자',
  income_year          SMALLINT UNSIGNED NOT NULL COMMENT '귀속 연도',
  annual_amount        DECIMAL(18,2)   NOT NULL COMMENT '연간 소득(KRW)',
  source               ENUM('USER_DECLARED','DOCUMENT','TRANSACTION_ESTIMATE') NOT NULL COMMENT '출처(거래 합계는 추정값일 뿐 공식 인정소득 아님)',
  document_id          BIGINT UNSIGNED NULL COMMENT '소득금액증명 등',
  verification_status  ENUM('DECLARED','NEEDS_EVIDENCE','VERIFIED','REJECTED') NOT NULL DEFAULT 'DECLARED' COMMENT '증빙 상태',
  created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  updated_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정',
  PRIMARY KEY (id),
  CONSTRAINT uq_income_records_year UNIQUE (user_id, income_year, source),
  CONSTRAINT fk_income_records_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_income_records_doc FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE SET NULL,
  CONSTRAINT ck_income_records_amount CHECK (annual_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 연도별 인정소득(E-7-4 최근 2년 평균, FR-026)';

-- ---------------------------------------------------------------------
-- D. 정책 문서 · E-7-4 규칙 · 평가 · 로드맵
-- ---------------------------------------------------------------------

-- 비자 정책 원문 정보(RAG 근거 문서) 저장용 테이블 --
CREATE TABLE policy_documents (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '정책 문서 ID(= MongoDB 청크 메타 policy_document_id)',
  source_type         ENUM('LAW','ADMIN_RULE','NOTICE','GUIDE','SCORE_TABLE') NOT NULL COMMENT '법령/행정규칙/공고/안내/점수표',
  title               VARCHAR(300)    NOT NULL COMMENT '문서 제목',
  issuing_agency      VARCHAR(100)    NOT NULL COMMENT '발행기관',
  source_url          VARCHAR(1000)   NOT NULL COMMENT '공식 출처 URL',
  external_id         VARCHAR(100)    NULL COMMENT '법제처 MST/ID 등',
  visa_type_code      VARCHAR(20)     NULL COMMENT '관련 체류자격',
  published_on        DATE            NULL COMMENT '발행일',
  effective_from      DATE            NULL COMMENT '시행일(NULL=확정 근거로 사용 불가)',
  effective_to        DATE            NULL COMMENT '종료일(미포함)',
  policy_version      VARCHAR(40)     NOT NULL COMMENT '예: E74-2026-v1',
  content_hash        CHAR(64)        NOT NULL COMMENT '변경 감지',
  object_key          VARCHAR(512)    NULL COMMENT 'R2 hanstep-visa-policies 원문 파일 키',
  review_status       ENUM('DRAFT','APPROVED','NEEDS_REVIEW','RETIRED') NOT NULL DEFAULT 'DRAFT' COMMENT 'APPROVED만 검색 대상',
  chroma_collection   VARCHAR(100)    NULL COMMENT 'MongoDB 컬렉션 이름(예: visa_policy_chunks, 컬럼명은 이전 Chroma 기준)',
  chunk_count         INT UNSIGNED    NULL COMMENT '적재 청크 수',
  embedding_model     VARCHAR(100)    NULL COMMENT '임베딩 모델',
  ingested_at         DATETIME(3)     NULL COMMENT '인덱싱 완료',
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '수집',
  updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정',
  PRIMARY KEY (id),
  CONSTRAINT uq_policy_documents_hash UNIQUE (content_hash),
  KEY ix_policy_documents_visa (visa_type_code, review_status, effective_from),
  CONSTRAINT ck_policy_documents_period CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to > effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 비자 정책 원문 메타(청크·벡터는 MongoDB Vector Search)';

-- E-7-4 점수표 버전 저장용 테이블 --
CREATE TABLE e74_rule_sets (
  id                         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  rule_version               VARCHAR(40)     NOT NULL COMMENT 'E74-2026-v1',
  effective_from             DATE            NOT NULL COMMENT '적용 시작',
  effective_to               DATE            NULL COMMENT '적용 종료(미포함)',
  required_total_score       SMALLINT UNSIGNED NULL COMMENT '기준 총점(공식 확인 전 NULL, 확인되면 예: 200)',
  source_policy_document_id  BIGINT UNSIGNED NULL COMMENT '근거 공고 문서',
  approval_status            ENUM('DRAFT','APPROVED','RETIRED') NOT NULL DEFAULT 'DRAFT' COMMENT 'APPROVED만 계산에 사용',
  note                       VARCHAR(500)    NULL COMMENT '비고',
  created_at                 DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  PRIMARY KEY (id),
  CONSTRAINT uq_e74_rule_sets_version UNIQUE (rule_version),
  CONSTRAINT fk_e74_rule_sets_doc FOREIGN KEY (source_policy_document_id) REFERENCES policy_documents (id),
  CONSTRAINT ck_e74_rule_sets_period CHECK (effective_to IS NULL OR effective_to > effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='점수표는 DB에 숫자로 저장하고, 계산은 코드(규칙 엔진)';

-- E-7-4 배점/요건 항목 별 저장용 테이블 --
CREATE TABLE e74_rules (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  rule_set_id      BIGINT UNSIGNED NOT NULL COMMENT '규칙 버전',
  rule_code        VARCHAR(60)     NOT NULL COMMENT 'INCOME_30_35, TENURE_3Y, REQ_STAY_PERIOD 등',
  category         ENUM('BASE_INCOME','BASE_KOREAN','BASE_AGE','BONUS','PENALTY','REQUIREMENT','EXCLUSION') NOT NULL COMMENT '분류',
  condition_json   JSON            NOT NULL COMMENT '판정 조건(규칙 엔진이 해석)',
  points           SMALLINT        NULL COMMENT '배점(감점은 음수, 요건은 NULL)',
  exclusive_group  VARCHAR(50)     NULL COMMENT '같은 그룹 내 중복 가점 제한',
  industry_type    VARCHAR(30)     NULL COMMENT '업종 특례(NULL=전체)',
  actor            ENUM('USER','EMPLOYER','GOVERNMENT') NOT NULL DEFAULT 'USER' COMMENT '충족 주체(고용추천=EMPLOYER)',
  source_page      VARCHAR(50)     NULL COMMENT '근거 문서 페이지/조항',
  description      VARCHAR(300)    NOT NULL COMMENT '설명',
  PRIMARY KEY (id),
  CONSTRAINT uq_e74_rules_code UNIQUE (rule_set_id, rule_code),
  CONSTRAINT fk_e74_rules_set FOREIGN KEY (rule_set_id) REFERENCES e74_rule_sets (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] E-7-4 배점·요건 항목';

-- E-7-4 점수 계산 결과 저장용 테이블 --
CREATE TABLE e74_assessments (
  id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '평가 ID',
  user_id                 BIGINT UNSIGNED NOT NULL COMMENT '사용자',
  rule_set_id             BIGINT UNSIGNED NOT NULL COMMENT '적용 규칙 버전',
  request_key             VARCHAR(100)    NOT NULL COMMENT '멱등키(Idempotency-Key 또는 입력 해시)',
  as_of_date              DATE            NOT NULL COMMENT '평가 기준일(나이·근속 계산)',
  is_scenario             BOOLEAN         NOT NULL DEFAULT FALSE COMMENT '가정 시나리오 계산 여부',
  scenario_json           JSON            NULL COMMENT '가정 조건(TOPIK 3급 취득 등)',
  input_snapshot          JSON            NOT NULL COMMENT '계산 입력 스냅샷(프로필 변경에도 재현 가능)',
  base_score              SMALLINT        NULL COMMENT '기본 점수',
  bonus_score             SMALLINT        NULL COMMENT '가점',
  penalty_score           SMALLINT        NULL COMMENT '감점(자기신고 기반)',
  total_score             SMALLINT        NULL COMMENT '총점',
  required_score          SMALLINT        NULL COMMENT '기준 점수',
  score_requirement_met   BOOLEAN         NULL COMMENT '점수 충족(요건 충족과 별개)',
  eligibility_status      ENUM('ELIGIBLE','INELIGIBLE','NEEDS_EVIDENCE','NEEDS_REVIEW') NOT NULL COMMENT '사전진단 결과(승인 아님)',
  created_at              DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '계산 시각',
  PRIMARY KEY (id),
  CONSTRAINT uq_e74_assessments_req UNIQUE (user_id, request_key),
  KEY ix_e74_assessments_user (user_id, is_scenario, created_at),
  CONSTRAINT fk_e74_assessments_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_e74_assessments_set FOREIGN KEY (rule_set_id) REFERENCES e74_rule_sets (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] E-7-4 평가 결과(append-only, 덮어쓰지 않음 FR-031)';

-- E-7-4 항목별 내역 점수 저장용 테이블 --
CREATE TABLE e74_assessment_items (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  assessment_id   BIGINT UNSIGNED NOT NULL COMMENT '평가',
  rule_code       VARCHAR(60)     NOT NULL COMMENT '적용 규칙 코드',
  category        ENUM('BASE_INCOME','BASE_KOREAN','BASE_AGE','BONUS','PENALTY','REQUIREMENT','EXCLUSION') NOT NULL COMMENT '분류',
  status          ENUM('MET','NOT_MET','NEEDS_EVIDENCE','NEEDS_REVIEW','NOT_APPLICABLE') NOT NULL COMMENT '항목 판정',
  earned_points   SMALLINT        NULL COMMENT '획득 점수',
  message_code    VARCHAR(80)     NULL COMMENT 'i18n 메시지 키',
  evidence_json   JSON            NULL COMMENT '판정 근거(사용한 입력 필드·증빙 ID)',
  PRIMARY KEY (id),
  CONSTRAINT uq_e74_assessment_items UNIQUE (assessment_id, rule_code),
  CONSTRAINT fk_e74_assessment_items_a FOREIGN KEY (assessment_id) REFERENCES e74_assessments (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 항목별 점수·요건 판정';

-- 비자 전환 로드맵 저장용 테이블 --
CREATE TABLE visa_roadmaps (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '로드맵 ID',
  user_id             BIGINT UNSIGNED NOT NULL COMMENT '사용자',
  assessment_id       BIGINT UNSIGNED NOT NULL COMMENT '기준 평가',
  goal_visa_code      VARCHAR(20)     NOT NULL DEFAULT 'E-7-4' COMMENT '목표 체류자격',
  warnings_json       JSON            NULL COMMENT '경고(규칙 변경 등)',
  model_id            VARCHAR(60)     NULL COMMENT '설명 생성 LLM',
  prompt_version      VARCHAR(40)     NULL COMMENT '프롬프트 버전',
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  PRIMARY KEY (id),
  KEY ix_visa_roadmaps_user (user_id, created_at),
  CONSTRAINT fk_visa_roadmaps_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_visa_roadmaps_assessment FOREIGN KEY (assessment_id) REFERENCES e74_assessments (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 비자 성장 로드맵(재조회 시 LLM 재호출 없음)';

-- 로드맵 단계(STEP 1~4) 저장용 테이블 --
CREATE TABLE visa_roadmap_steps (
  id                          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '단계 ID',
  roadmap_id                  BIGINT UNSIGNED NOT NULL COMMENT '로드맵',
  step_order                  SMALLINT UNSIGNED NOT NULL COMMENT '순서',
  title                       VARCHAR(200)    NOT NULL COMMENT '단계 제목',
  description                 VARCHAR(1000)   NULL COMMENT '설명',
  related_rule_code           VARCHAR(60)     NULL COMMENT '관련 규칙(TOPIK, TENURE_3Y 등)',
  estimated_additional_score  SMALLINT        NULL COMMENT '예상 추가 점수(실제 획득 아님)',
  target_date                 DATE            NULL COMMENT '권장 완료 시점(체류 만료일 등에서 계산)',
  scenario_assessment_id      BIGINT UNSIGNED NULL COMMENT '같은 규칙 엔진으로 재계산한 시나리오 평가(FR-033)',
  actor                       ENUM('USER','EMPLOYER','GOVERNMENT') NOT NULL DEFAULT 'USER' COMMENT '실행 주체',
  required_evidence_json      JSON            NULL COMMENT '필요 증빙 목록',
  status                      ENUM('RECOMMENDED','IN_PROGRESS','DONE','SKIPPED') NOT NULL DEFAULT 'RECOMMENDED' COMMENT '진행 상태',
  PRIMARY KEY (id),
  CONSTRAINT uq_visa_roadmap_steps_order UNIQUE (roadmap_id, step_order),
  CONSTRAINT fk_visa_roadmap_steps_roadmap FOREIGN KEY (roadmap_id) REFERENCES visa_roadmaps (id) ON DELETE CASCADE,
  CONSTRAINT fk_visa_roadmap_steps_scn FOREIGN KEY (scenario_assessment_id) REFERENCES e74_assessments (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 로드맵 실행 단계';

-- ---------------------------------------------------------------------
-- E. 금융 원천 데이터 (Mock. 마이데이터 흐름 참고, 필드명은 자체 정의)
-- ---------------------------------------------------------------------

-- 금융기관 목록(신한은행·신한카드) 저장용 테이블 --
CREATE TABLE institutions (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  institution_code  VARCHAR(50)     NOT NULL COMMENT '기관 코드',
  name              VARCHAR(200)    NOT NULL COMMENT '기관명',
  name_ko           VARCHAR(100)    NULL COMMENT '화면 표시명(한국어)',
  institution_type  ENUM('BANK','CARD','TELECOM','BILLER','PUBLIC','OTHER') NOT NULL COMMENT '업권',
  PRIMARY KEY (id),
  CONSTRAINT uq_institutions_code UNIQUE (institution_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 금융기관·청구기관 사전';

-- 사용자별 마이데이터 연결 상태 저장용 테이블 --
CREATE TABLE data_connections (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  user_id         BIGINT UNSIGNED NOT NULL COMMENT '사용자',
  institution_id  BIGINT UNSIGNED NOT NULL COMMENT '기관',
  consent_id      BIGINT UNSIGNED NOT NULL COMMENT 'MYDATA 동의',
  status          ENUM('PENDING','CONNECTED','EXPIRED','REVOKED','ERROR') NOT NULL DEFAULT 'PENDING' COMMENT '연동 상태(동의 상태와 별개)',
  requested_scopes_json JSON      NULL COMMENT '불러올 정보(ACCOUNT_BALANCE, TRANSACTIONS, CARD_USAGE, LOANS)',
  lookback_months SMALLINT UNSIGNED NOT NULL DEFAULT 12 COMMENT '조회 기간(개월, ON-003B 최근 12개월)',
  last_synced_at  DATETIME(3)     NULL COMMENT '최근 수집',
  error_code      VARCHAR(50)     NULL COMMENT '오류',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정',
  PRIMARY KEY (id),
  CONSTRAINT uq_data_connections UNIQUE (user_id, institution_id),
  CONSTRAINT fk_data_connections_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_data_connections_inst FOREIGN KEY (institution_id) REFERENCES institutions (id),
  CONSTRAINT fk_data_connections_consent FOREIGN KEY (consent_id) REFERENCES user_consents (id) ON DELETE CASCADE,
  CONSTRAINT ck_data_connections_lookback CHECK (lookback_months BETWEEN 1 AND 60)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 기관별 연동 상태(MVP는 Mock 연결)';

-- 거래 데이터 수집 작업 기록(Mock 여부 포함) 저장용 테이블 --
CREATE TABLE ingestion_batches (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '수집 배치 ID',
  user_id        BIGINT UNSIGNED NOT NULL COMMENT '사용자',
  connection_id  BIGINT UNSIGNED NULL COMMENT '연동',
  source_type    ENUM('MYDATA','MOCK','USER_INPUT') NOT NULL COMMENT '출처',
  is_mock        BOOLEAN         NOT NULL DEFAULT TRUE COMMENT 'Mock 여부(화면 표시 NFR-014)',
  scenario_code  VARCHAR(50)     NULL COMMENT 'Mock 페르소나(SURPLUS, HIGH_REMITTANCE, IRREGULAR_INCOME)',
  request_key    VARCHAR(100)    NOT NULL COMMENT '재실행 멱등키',
  status         ENUM('PENDING','SUCCEEDED','PARTIAL','FAILED') NOT NULL DEFAULT 'PENDING' COMMENT '상태',
  coverage_from  DATE            NULL COMMENT '수집 시작일(완전 관측 월 판정)',
  coverage_to    DATE            NULL COMMENT '수집 종료일',
  record_count   INT UNSIGNED    NULL COMMENT '적재 건수',
  error_code     VARCHAR(50)     NULL COMMENT '오류',
  started_at     DATETIME(3)     NOT NULL COMMENT '시작',
  finished_at    DATETIME(3)     NULL COMMENT '종료',
  PRIMARY KEY (id),
  CONSTRAINT uq_ingestion_batches_req UNIQUE (user_id, request_key),
  CONSTRAINT fk_ingestion_batches_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_ingestion_batches_conn FOREIGN KEY (connection_id) REFERENCES data_connections (id) ON DELETE CASCADE,
  CONSTRAINT ck_ingestion_batches_mock CHECK (source_type <> 'MOCK' OR is_mock = TRUE),
  CONSTRAINT ck_ingestion_batches_time CHECK (finished_at IS NULL OR finished_at >= started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 수집 단위 출처·Mock 구분·관측 기간';

-- 거래 카테고리 목록 저장용 테이블 --
CREATE TABLE transaction_categories (
  code         VARCHAR(30)     NOT NULL COMMENT 'SALARY, HOUSING, REMITTANCE ...',
  parent_code  VARCHAR(30)     NULL COMMENT '상위 분류',
  name_en      VARCHAR(60)     NOT NULL COMMENT '영문명',
  name_ko      VARCHAR(60)     NOT NULL COMMENT '한글명',
  flow         ENUM('INCOME','EXPENSE','NEUTRAL') NOT NULL COMMENT '집계 방향(NEUTRAL=집계 제외)',
  sort_order   SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '표시 순서',
  PRIMARY KEY (code),
  CONSTRAINT fk_transaction_categories_parent FOREIGN KEY (parent_code) REFERENCES transaction_categories (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 거래 카테고리 마스터(시드)';

-- 계좌·잔액 정보 저장용 테이블 --
CREATE TABLE financial_accounts (
  id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '계좌 ID',
  user_id               BIGINT UNSIGNED NOT NULL COMMENT '소유자',
  institution_id        BIGINT UNSIGNED NOT NULL COMMENT '기관',
  batch_id              BIGINT UNSIGNED NOT NULL COMMENT '최초 적재 배치(목인지 아닌지 구분용)',
  external_account_key  VARCHAR(100)    NOT NULL COMMENT '원천 계좌 식별자(조회·재수집 매칭용, Mock은 고정 ID). 실연동 시 계좌번호 원문이면 암호화 저장',
  account_no_masked     VARCHAR(40)     NOT NULL COMMENT '화면 표시 전용 마스킹 번호(조회에 사용 안 함, 전체 번호 저장 금지)',
  account_type          ENUM('DEMAND','SAVINGS','INSTALLMENT','FOREIGN_CURRENCY','CARD','OTHER') NOT NULL COMMENT '입출금/예금/적금/외화/카드(신용·체크카드)',
  product_name          VARCHAR(200)    NULL COMMENT '상품명',
  currency              CHAR(3)         NOT NULL DEFAULT 'KRW' COMMENT '통화',
  balance               DECIMAL(18,2)   NOT NULL DEFAULT 0 COMMENT '잔액',
  balance_as_of         DATETIME(3)     NULL COMMENT '잔액 기준시각',
  is_salary_account     BOOLEAN         NOT NULL DEFAULT FALSE COMMENT '급여 계좌',
  opened_on             DATE            NULL COMMENT '개설일',
  closed_on             DATE            NULL COMMENT '해지일',
  created_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  updated_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정',
  PRIMARY KEY (id),
  CONSTRAINT uq_financial_accounts_ext UNIQUE (user_id, institution_id, external_account_key),
  CONSTRAINT fk_financial_accounts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_financial_accounts_inst FOREIGN KEY (institution_id) REFERENCES institutions (id),
  CONSTRAINT fk_financial_accounts_batch FOREIGN KEY (batch_id) REFERENCES ingestion_batches (id) ON DELETE CASCADE,
  CONSTRAINT ck_financial_accounts_period CHECK (closed_on IS NULL OR opened_on IS NULL OR closed_on >= opened_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] Mock 계좌';

-- 거래내역 저장용 테이블 --
CREATE TABLE financial_transactions (
  id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '거래 ID',
  user_id                BIGINT UNSIGNED NOT NULL COMMENT '소유자(조회 성능용 중복)',
  account_id             BIGINT UNSIGNED NOT NULL COMMENT '계좌',
  batch_id               BIGINT UNSIGNED NOT NULL COMMENT '적재 배치',
  external_txn_key       VARCHAR(100)    NOT NULL COMMENT '원천 거래 키(재수집 중복 방지, 같은 거래는 항상 같은 키)',
  transacted_at          DATETIME(3)     NOT NULL COMMENT '거래 시각(UTC)',
  booked_date            DATE            NOT NULL COMMENT '거래일(KST 기준, 월 집계 기준)',
  direction              ENUM('INCOME','EXPENSE') NOT NULL COMMENT '입출금 방향(수입 인정 여부는 kind로 판정)',
  amount                 DECIMAL(18,2)   NOT NULL COMMENT '절대금액(>0)',
  currency               CHAR(3)         NOT NULL DEFAULT 'KRW' COMMENT '통화(MVP는 KRW만 분석)',
  counterparty_name      VARCHAR(200)    NULL COMMENT '거래처',
  counterparty_bank      VARCHAR(100)    NULL COMMENT '상대 금융기관명(해외송금 수취은행 등, 원천 제공 시)',
  memo                   VARCHAR(200)    NULL COMMENT '적요(비식별)',
  category_code          VARCHAR(30)     NOT NULL DEFAULT 'OTHER' COMMENT '카테고리(미분류=OTHER, FR-010)',
  transaction_kind       ENUM('NORMAL','OWN_TRANSFER','SAVINGS_TRANSFER','LOAN_DISBURSEMENT','LOAN_REPAYMENT','CARD_SETTLEMENT') NOT NULL DEFAULT 'NORMAL' COMMENT '중복 집계 차단용 거래 종류(FR-011)',
  classification_source  ENUM('SOURCE','RULE','USER','MODEL_CANDIDATE') NOT NULL DEFAULT 'SOURCE' COMMENT '분류 근거(USER는 재분류가 덮지 않음)',
  created_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '적재',
  updated_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정',
  PRIMARY KEY (id),
  CONSTRAINT uq_financial_transactions_ext UNIQUE (account_id, external_txn_key),
  KEY ix_financial_transactions_user_date (user_id, booked_date),
  KEY ix_financial_transactions_user_cat (user_id, category_code, booked_date),
  CONSTRAINT fk_financial_transactions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_financial_transactions_acct FOREIGN KEY (account_id) REFERENCES financial_accounts (id) ON DELETE CASCADE,
  CONSTRAINT fk_financial_transactions_batch FOREIGN KEY (batch_id) REFERENCES ingestion_batches (id) ON DELETE CASCADE,
  CONSTRAINT fk_financial_transactions_cat FOREIGN KEY (category_code) REFERENCES transaction_categories (code),
  CONSTRAINT ck_financial_transactions_amount CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] Mock 거래내역 + 분류';

-- 대출 정보 저장용 테이블 (후순위 가능: 데모 페르소나 대출 없음, 사용 화면 FN-001은 P1) --
CREATE TABLE loans (
  id                         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '대출 ID',
  user_id                    BIGINT UNSIGNED NOT NULL COMMENT '소유자',
  institution_id             BIGINT UNSIGNED NOT NULL COMMENT '기관',
  batch_id                   BIGINT UNSIGNED NOT NULL COMMENT '적재 배치',
  external_loan_key          VARCHAR(100)    NOT NULL COMMENT '원천 대출 키',
  loan_type                  VARCHAR(50)     NULL COMMENT '신용/전세/기타',
  principal                  DECIMAL(18,2)   NOT NULL COMMENT '약정 원금',
  outstanding_balance        DECIMAL(18,2)   NOT NULL COMMENT '잔액(마이데이터 제공)',
  annual_interest_rate       DECIMAL(7,5)    NULL COMMENT '연이율 소수(0.05=5%)',
  repayment_method           ENUM('EQUAL_PAYMENT','EQUAL_PRINCIPAL','BULLET','UNKNOWN') NOT NULL DEFAULT 'UNKNOWN' COMMENT '상환방식',
  scheduled_payment_amount   DECIMAL(18,2)   NULL COMMENT '월 상환액',
  scheduled_payment_source   ENUM('PROVIDED','CALCULATED','UNKNOWN') NOT NULL DEFAULT 'UNKNOWN' COMMENT '월 상환액 출처(표준 항목 아님 → 계산)',
  started_on                 DATE            NOT NULL COMMENT '실행일',
  maturity_on                DATE            NULL COMMENT '만기일',
  status                     ENUM('ACTIVE','CLOSED') NOT NULL DEFAULT 'ACTIVE' COMMENT '상태',
  created_at                 DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  updated_at                 DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정',
  PRIMARY KEY (id),
  CONSTRAINT uq_loans_ext UNIQUE (user_id, institution_id, external_loan_key),
  CONSTRAINT fk_loans_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_loans_inst FOREIGN KEY (institution_id) REFERENCES institutions (id),
  CONSTRAINT fk_loans_batch FOREIGN KEY (batch_id) REFERENCES ingestion_batches (id) ON DELETE CASCADE,
  CONSTRAINT ck_loans_amounts CHECK (principal >= 0 AND outstanding_balance >= 0),
  CONSTRAINT ck_loans_rate CHECK (annual_interest_rate IS NULL OR annual_interest_rate >= 0),
  CONSTRAINT ck_loans_period CHECK (maturity_on IS NULL OR maturity_on >= started_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0 데이터/P1 기능] 기존 부채(대출 사전진단 FR-061 입력)';

-- ---------------------------------------------------------------------
-- F. 금융 분석 결과 (태건 P0)
-- ---------------------------------------------------------------------

-- 금융 분석 결과(월평균 수입·지출·송금·남는 돈) 저장용 테이블 --
CREATE TABLE financial_analyses (
  id                        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '분석 ID',
  user_id                   BIGINT UNSIGNED NOT NULL COMMENT '사용자',
  request_key               VARCHAR(100)    NOT NULL COMMENT '멱등키(기간+원천 지문+엔진 버전 해시 가능)',
  period_from               DATE            NOT NULL COMMENT '조회 시작',
  period_to                 DATE            NOT NULL COMMENT '조회 종료',
  status                    ENUM('COMPLETED','FAILED') NOT NULL COMMENT '결과 상태',
  engine_version            VARCHAR(40)     NOT NULL COMMENT '계산 엔진 버전',
  source_txn_count          INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '입력 거래 수',
  source_fingerprint        CHAR(64)        NULL COMMENT '입력 거래 집합 해시(결과 재사용 판단)',
  currency                  CHAR(3)         NOT NULL DEFAULT 'KRW' COMMENT '통화',
  avg_monthly_income        DECIMAL(18,2)   NULL COMMENT '완전 관측 월 평균 수입(없으면 NULL)',
  avg_monthly_expense       DECIMAL(18,2)   NULL COMMENT '평균 지출(송금 포함)',
  avg_monthly_remittance    DECIMAL(18,2)   NULL COMMENT '평균 송금(지출의 하위 항목)',
  avg_monthly_surplus       DECIMAL(18,2)   NULL COMMENT '평균 잉여(음수 허용 — 경고 저장용)',
  remittance_ratio          DECIMAL(7,4)    NULL COMMENT '송금/수입(수입 0이면 NULL)',
  observed_month_count      SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '완전 관측 월 수(평균 분모)',
  missing_months_json       JSON            NULL COMMENT '수집 누락 월',
  partial_months_json       JSON            NULL COMMENT '부분 월',
  warnings_json             JSON            NULL COMMENT '경고 코드 목록',
  monthly_json              JSON            NULL COMMENT '월별 수입·지출·송금·잉여',
  category_json             JSON            NULL COMMENT '카테고리별 기간 합계',
  excluded_json             JSON            NULL COMMENT '집계 제외 거래와 사유',
  error_code                VARCHAR(50)     NULL COMMENT 'FAILED 사유',
  created_at                DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  PRIMARY KEY (id),
  CONSTRAINT uq_financial_analyses_req UNIQUE (user_id, request_key),
  KEY ix_financial_analyses_user (user_id, created_at),
  CONSTRAINT fk_financial_analyses_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT ck_financial_analyses_period CHECK (period_to >= period_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 현금흐름 분석 결과(append-only, 홈·챗봇 공용 수치 원천)';

-- ---------------------------------------------------------------------
-- G. 비동기 작업 · AI 채팅
-- ---------------------------------------------------------------------

-- 비동기 AI 작업(OCR 등) 상태 저장용 테이블 --
CREATE TABLE ai_jobs (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Job ID(= MQ jobId)',
  user_id           BIGINT UNSIGNED NULL COMMENT '요청 사용자(정책 동기화는 NULL)',
  job_type          ENUM('OCR','POLICY_INGEST','DOCUMENT_ANALYSIS') NOT NULL COMMENT '작업 유형',
  resource_type     ENUM('DOCUMENT','POLICY_DOCUMENT','POLICY_SOURCE') NOT NULL COMMENT '대상 리소스 종류',
  resource_id       BIGINT UNSIGNED NULL COMMENT '대상 리소스 ID',
  idempotency_key   VARCHAR(100)    NOT NULL COMMENT '중복 요청 방지',
  correlation_id    VARCHAR(64)     NOT NULL COMMENT '요청 추적(requestId)',
  status            ENUM('PENDING','QUEUED','PROCESSING','COMPLETED','FAILED') NOT NULL DEFAULT 'PENDING' COMMENT '팀 공통 상태 enum',
  publish_status    ENUM('NOT_PUBLISHED','PUBLISHED','PUBLISH_FAILED') NOT NULL DEFAULT 'NOT_PUBLISHED' COMMENT 'DB-MQ 정합성(미발행 재시도)',
  retry_count       TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '재시도 횟수',
  max_retries       TINYINT UNSIGNED NOT NULL DEFAULT 3 COMMENT '최대 재시도',
  result_reference  VARCHAR(100)    NULL COMMENT '결과 참조(document:42 등)',
  error_code        VARCHAR(50)     NULL COMMENT 'OCR_FAILED, TIMEOUT ...',
  error_message     VARCHAR(500)    NULL COMMENT '오류 요약(개인정보 금지)',
  created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '접수',
  queued_at         DATETIME(3)     NULL COMMENT '발행',
  started_at        DATETIME(3)     NULL COMMENT '처리 시작',
  completed_at      DATETIME(3)     NULL COMMENT '완료/실패',
  updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정(고아 작업 탐지)',
  PRIMARY KEY (id),
  CONSTRAINT uq_ai_jobs_idem UNIQUE (job_type, idempotency_key),
  KEY ix_ai_jobs_user (user_id, created_at),
  KEY ix_ai_jobs_status (status, updated_at),
  CONSTRAINT fk_ai_jobs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT ck_ai_jobs_done CHECK (status NOT IN ('COMPLETED','FAILED') OR completed_at IS NOT NULL),
  CONSTRAINT ck_ai_jobs_retry CHECK (retry_count <= max_retries)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] RabbitMQ 비동기 작업 단일 진실(Spring만 갱신)';

-- AI 채팅 대화방 저장용 테이블 --
CREATE TABLE chat_sessions (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '세션 ID(= LangGraph thread 연결 키)',
  user_id          BIGINT UNSIGNED NOT NULL COMMENT '소유자',
  title            VARCHAR(200)    NULL COMMENT '자동 생성 제목',
  status           ENUM('ACTIVE','ARCHIVED') NOT NULL DEFAULT 'ACTIVE' COMMENT '상태',
  last_message_at  DATETIME(3)     NULL COMMENT '최근 메시지(목록 정렬 FR-043)',
  created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  PRIMARY KEY (id),
  KEY ix_chat_sessions_user (user_id, status, last_message_at),
  CONSTRAINT fk_chat_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 채팅 세션';

-- AI 채팅 메시지 저장용 테이블 --
CREATE TABLE chat_messages (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '메시지 ID',
  session_id         BIGINT UNSIGNED NOT NULL COMMENT '세션',
  role               ENUM('USER','ASSISTANT') NOT NULL COMMENT '발화자',
  content            TEXT            NOT NULL COMMENT '본문(개인식별번호 제거 후 저장)',
  blocks_json        JSON            NULL COMMENT '구조화 카드·버튼(계산 결과, 이체 화면 이동 등). 수치는 도구 결과에서만 채움',
  status             ENUM('COMPLETED','STREAMING','PARTIAL','FAILED') NOT NULL DEFAULT 'COMPLETED' COMMENT 'PARTIAL=스트림 중단',
  client_message_id  VARCHAR(64)     NULL COMMENT 'FE 생성 UUID(중복 전송 방지, USER만)',
  run_id             VARCHAR(64)     NULL COMMENT 'Agent 실행 ID(USER·ASSISTANT 쌍 연결)',
  reply_to_id        BIGINT UNSIGNED NULL COMMENT '응답 대상 USER 메시지',
  model_id           VARCHAR(60)     NULL COMMENT 'LLM',
  prompt_version     VARCHAR(40)     NULL COMMENT '프롬프트 버전',
  latency_ms         INT UNSIGNED    NULL COMMENT '응답 완료까지 시간',
  error_code         VARCHAR(50)     NULL COMMENT '오류',
  created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  PRIMARY KEY (id),
  CONSTRAINT uq_chat_messages_client UNIQUE (session_id, client_message_id),
  KEY ix_chat_messages_session (session_id, created_at, id),
  KEY ix_chat_messages_run (run_id),
  CONSTRAINT fk_chat_messages_session FOREIGN KEY (session_id) REFERENCES chat_sessions (id) ON DELETE CASCADE,
  CONSTRAINT fk_chat_messages_reply FOREIGN KEY (reply_to_id) REFERENCES chat_messages (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 채팅 메시지(스트림 청크별 저장 금지 FR-050)';

-- AI 답변 중 도구 호출 기록 저장용 테이블 --
CREATE TABLE chat_tool_calls (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  message_id       BIGINT UNSIGNED NOT NULL COMMENT 'ASSISTANT 메시지',
  run_id           VARCHAR(64)     NOT NULL COMMENT 'Agent 실행 ID',
  call_order       SMALLINT UNSIGNED NOT NULL COMMENT '호출 순서',
  tool_name        VARCHAR(60)     NOT NULL COMMENT 'get_financial_analysis, calculate_e74_score ...',
  arguments_json   JSON            NULL COMMENT '인자(개인정보 제거)',
  status           ENUM('SUCCEEDED','FAILED','TIMEOUT','REJECTED') NOT NULL COMMENT 'REJECTED=인자 검증 실패',
  result_ref_type  VARCHAR(40)     NULL COMMENT 'FINANCIAL_ANALYSIS, E74_ASSESSMENT, POLICY_DOCUMENT, JOB ...',
  result_ref_id    BIGINT UNSIGNED NULL COMMENT '결과 행 ID',
  latency_ms       INT UNSIGNED    NULL COMMENT '도구 실행시간',
  error_code       VARCHAR(50)     NULL COMMENT '오류',
  created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '기록',
  PRIMARY KEY (id),
  CONSTRAINT uq_chat_tool_calls_order UNIQUE (message_id, call_order),
  KEY ix_chat_tool_calls_tool (tool_name, status),
  CONSTRAINT fk_chat_tool_calls_message FOREIGN KEY (message_id) REFERENCES chat_messages (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 도구 호출 기록(AI 평가 지표: 호출 정확도·성공률·지연)';

-- AI 답변 근거 출처 저장용 테이블 --
CREATE TABLE chat_message_sources (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  message_id   BIGINT UNSIGNED NOT NULL COMMENT 'ASSISTANT 메시지',
  source_type  ENUM('POLICY_DOCUMENT','PRODUCT_DOCUMENT','FINANCIAL_ANALYSIS','E74_ASSESSMENT','VISA_ROADMAP') NOT NULL COMMENT '근거 종류',
  source_id    BIGINT UNSIGNED NOT NULL COMMENT '근거 행 ID',
  chunk_ref    VARCHAR(100)    NULL COMMENT 'MongoDB 청크 ID',
  page_label   VARCHAR(50)     NULL COMMENT '페이지·조항',
  PRIMARY KEY (id),
  KEY ix_chat_message_sources_msg (message_id),
  CONSTRAINT fk_chat_message_sources_msg FOREIGN KEY (message_id) REFERENCES chat_messages (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 답변 근거(sources:[{type,id}])';

-- ---------------------------------------------------------------------
-- H. 금융상품 RAG (태건 P1)
-- ---------------------------------------------------------------------

-- 금융상품 정보 저장용 테이블 (후순위: P1 금융상품 RAG, 2차 개발) --
CREATE TABLE financial_products (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '상품 ID',
  institution_id  BIGINT UNSIGNED NOT NULL COMMENT '기관',
  product_code    VARCHAR(100)    NOT NULL COMMENT '상품 코드',
  name            VARCHAR(200)    NOT NULL COMMENT '공식 상품명',
  product_type    ENUM('DEPOSIT','SAVINGS','LOAN','REMITTANCE','CARD','OTHER') NOT NULL COMMENT '유형',
  currency        CHAR(3)         NOT NULL DEFAULT 'KRW' COMMENT '통화',
  summary         VARCHAR(1000)   NULL COMMENT '요약',
  terms_url       VARCHAR(1000)   NULL COMMENT '공식 안내 URL',
  source_type     ENUM('OFFICIAL','MANUAL','DEMO_FIXTURE') NOT NULL COMMENT '출처(DEMO_FIXTURE는 가상 상품)',
  is_synthetic    BOOLEAN         NOT NULL DEFAULT FALSE COMMENT '가상 상품 여부(실제 조건처럼 노출 금지)',
  review_status   ENUM('DRAFT','APPROVED','NEEDS_REVIEW','RETIRED') NOT NULL DEFAULT 'DRAFT' COMMENT '검토 상태',
  checked_at      DATETIME(3)     NOT NULL COMMENT '정보 확인 시각',
  effective_from  DATE            NOT NULL COMMENT '적용 시작',
  effective_to    DATE            NULL COMMENT '적용 종료(미포함)',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정',
  PRIMARY KEY (id),
  CONSTRAINT uq_financial_products_code UNIQUE (institution_id, product_code),
  CONSTRAINT fk_financial_products_inst FOREIGN KEY (institution_id) REFERENCES institutions (id),
  CONSTRAINT ck_financial_products_synth CHECK (source_type <> 'DEMO_FIXTURE' OR is_synthetic = TRUE),
  CONSTRAINT ck_financial_products_period CHECK (effective_to IS NULL OR effective_to > effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P1] 금융상품 카탈로그';

-- 상품설명서 원문 정보 저장용 테이블 (후순위: P1, 2차 개발) --
CREATE TABLE product_documents (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '상품 문서 ID(= MongoDB 청크 메타 product_document_id)',
  product_id        BIGINT UNSIGNED NOT NULL COMMENT '상품',
  doc_kind          ENUM('PRODUCT_DESCRIPTION','TERMS','RATE_SHEET','FOREIGNER_GUIDE') NOT NULL COMMENT '설명서/약관/금리/외국인 안내',
  document_version  VARCHAR(40)     NOT NULL COMMENT '문서 버전',
  source_url        VARCHAR(1000)   NOT NULL COMMENT '공식 원문',
  object_key        VARCHAR(512)    NULL COMMENT 'R2 hanstep-financial-products 원문 파일 키',
  content_hash      CHAR(64)        NOT NULL COMMENT '변경 감지',
  fetched_at        DATETIME(3)     NOT NULL COMMENT '수집 시각(시행일과 구분)',
  effective_from    DATE            NULL COMMENT '시행일',
  effective_to      DATE            NULL COMMENT '종료일(미포함)',
  review_status     ENUM('DRAFT','APPROVED','NEEDS_REVIEW','RETIRED') NOT NULL DEFAULT 'DRAFT' COMMENT 'APPROVED만 검색',
  embedding_model   VARCHAR(100)    NULL COMMENT '임베딩 모델·리비전',
  chunk_count       INT UNSIGNED    NULL COMMENT '청크 수(financial_product_chunks)',
  PRIMARY KEY (id),
  CONSTRAINT uq_product_documents_ver UNIQUE (product_id, doc_kind, document_version),
  CONSTRAINT fk_product_documents_product FOREIGN KEY (product_id) REFERENCES financial_products (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P1] 상품 원문 메타(청크는 MongoDB Vector Search)';

-- 상품 가입 조건 저장용 테이블 (후순위: P1, 2차 개발) --
CREATE TABLE product_eligibility_rules (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  product_id      BIGINT UNSIGNED NOT NULL COMMENT '상품',
  rule_code       VARCHAR(60)     NOT NULL COMMENT '규칙 코드',
  rule_version    VARCHAR(40)     NOT NULL COMMENT '규칙 버전',
  field_path      VARCHAR(100)    NOT NULL COMMENT '판정 필드(visa.stayExpiresOn 등)',
  operator        ENUM('PRESENT','EQ','NE','IN','GTE','LTE','BETWEEN') NOT NULL COMMENT '연산자',
  expected_json   JSON            NULL COMMENT '기대값',
  source_document_id BIGINT UNSIGNED NULL COMMENT '근거 문서',
  source_section  VARCHAR(100)    NULL COMMENT '근거 절',
  is_synthetic    BOOLEAN         NOT NULL DEFAULT FALSE COMMENT '가상 규칙',
  PRIMARY KEY (id),
  CONSTRAINT uq_product_eligibility_rules UNIQUE (product_id, rule_code, rule_version),
  CONSTRAINT fk_product_eligibility_rules_p FOREIGN KEY (product_id) REFERENCES financial_products (id),
  CONSTRAINT fk_product_eligibility_rules_d FOREIGN KEY (source_document_id) REFERENCES product_documents (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P1] 공개 가입조건 규칙(MET/NOT_MET/UNKNOWN 판정)';

-- 상품 추천 실행 기록 저장용 테이블 (후순위: P1, 2차 개발) --
CREATE TABLE product_recommendations (
  id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '추천 ID',
  user_id              BIGINT UNSIGNED NOT NULL COMMENT '사용자',
  request_json         JSON            NOT NULL COMMENT '목적·기간·금액 등 요청',
  analysis_id          BIGINT UNSIGNED NULL COMMENT '참조한 금융 분석',
  status               ENUM('COMPLETED','NEEDS_INFO','FAILED') NOT NULL COMMENT '결과 상태',
  contains_synthetic   BOOLEAN         NOT NULL DEFAULT FALSE COMMENT '가상 상품 포함',
  created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  PRIMARY KEY (id),
  KEY ix_product_recommendations_user (user_id, created_at),
  CONSTRAINT fk_product_recommendations_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_product_recommendations_an FOREIGN KEY (analysis_id) REFERENCES financial_analyses (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P1] 상품 추천 결과(가입·승인과 구분)';

-- 추천 상품 목록·조건 판정 저장용 테이블 (후순위: P1, 2차 개발) --
CREATE TABLE product_recommendation_items (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  recommendation_id   BIGINT UNSIGNED NOT NULL COMMENT '추천',
  product_id          BIGINT UNSIGNED NOT NULL COMMENT '상품',
  rank_no             SMALLINT UNSIGNED NOT NULL COMMENT '순위',
  condition_status    ENUM('MET','NOT_MET','UNKNOWN') NOT NULL COMMENT '공개 조건 판정',
  missing_fields_json JSON            NULL COMMENT '부족 정보',
  reasons_json        JSON            NULL COMMENT '추천 이유',
  source_refs_json    JSON            NULL COMMENT '근거 청크 참조',
  PRIMARY KEY (id),
  CONSTRAINT uq_product_recommendation_items UNIQUE (recommendation_id, rank_no),
  CONSTRAINT fk_product_rec_items_rec FOREIGN KEY (recommendation_id) REFERENCES product_recommendations (id) ON DELETE CASCADE,
  CONSTRAINT fk_product_rec_items_prod FOREIGN KEY (product_id) REFERENCES financial_products (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P1] 추천 후보';

-- ---------------------------------------------------------------------
-- I. 모의 대안신용평가 (재현 P1) — 학습 데이터는 서비스 DB 밖(R2 CSV)
-- ---------------------------------------------------------------------

-- 모의 금융 진단 모델 버전 저장용 테이블 (후순위: P1 재현, 2차 개발) --
CREATE TABLE credit_models (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  model_code       VARCHAR(80)     NOT NULL COMMENT '모델 코드',
  version          VARCHAR(40)     NOT NULL COMMENT '버전',
  algorithm        VARCHAR(60)     NOT NULL COMMENT 'LOGISTIC_REGRESSION 등',
  training_data    VARCHAR(300)    NOT NULL COMMENT '학습 데이터 설명(합성 여부 명시)',
  is_synthetic_trained BOOLEAN     NOT NULL DEFAULT TRUE COMMENT '합성 데이터 학습 여부',
  metrics_json     JSON            NULL COMMENT 'ROC-AUC, PR-AUC, 보정 지표',
  artifact_key     VARCHAR(512)    NULL COMMENT 'R2 모델 파일',
  status           ENUM('DRAFT','ACTIVE','RETIRED') NOT NULL DEFAULT 'DRAFT' COMMENT '상태',
  created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  PRIMARY KEY (id),
  CONSTRAINT uq_credit_models_ver UNIQUE (model_code, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P1] 모의 신용평가 모델 버전';

-- 모의 금융 진단 결과 저장용 테이블 (후순위: P1 재현, 2차 개발) --
CREATE TABLE credit_assessments (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '평가 ID',
  user_id         BIGINT UNSIGNED NOT NULL COMMENT '사용자',
  model_id        BIGINT UNSIGNED NOT NULL COMMENT '모델',
  consent_id      BIGINT UNSIGNED NOT NULL COMMENT 'CREDIT_EVALUATION 동의',
  request_key     VARCHAR(100)    NOT NULL COMMENT '멱등키',
  window_from     DATE            NOT NULL COMMENT '입력 기간 시작',
  window_to       DATE            NOT NULL COMMENT '입력 기간 종료',
  status          ENUM('COMPLETED','FAILED') NOT NULL COMMENT '상태',
  risk_probability DECIMAL(7,6)   NULL COMMENT '모의 위험 확률(실제 연체확률 아님)',
  risk_band       VARCHAR(20)     NULL COMMENT '구간',
  display_score   SMALLINT UNSIGNED NULL COMMENT '화면 표시용 0~100 모의 지표(실제 신용점수 아님)',
  usage_mode      ENUM('DEMO') NOT NULL DEFAULT 'DEMO' COMMENT '교육·시연 전용',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  PRIMARY KEY (id),
  CONSTRAINT uq_credit_assessments_req UNIQUE (user_id, request_key),
  KEY ix_credit_assessments_user (user_id, created_at),
  CONSTRAINT fk_credit_assessments_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_credit_assessments_model FOREIGN KEY (model_id) REFERENCES credit_models (id),
  CONSTRAINT fk_credit_assessments_consent FOREIGN KEY (consent_id) REFERENCES user_consents (id) ON DELETE CASCADE,
  CONSTRAINT ck_credit_assessments_prob CHECK (risk_probability IS NULL OR (risk_probability >= 0 AND risk_probability <= 1)),
  CONSTRAINT ck_credit_assessments_window CHECK (window_to >= window_from),
  CONSTRAINT ck_credit_assessments_display CHECK (display_score IS NULL OR display_score <= 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P1] 모의 대안신용평가 결과(실신용점수·대출 승인과 구분)';

-- 금융 진단 입력 지표·기여도 저장용 테이블 (후순위: P1 재현, 2차 개발) --
CREATE TABLE credit_feature_values (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  assessment_id   BIGINT UNSIGNED NOT NULL COMMENT '평가',
  feature_code    VARCHAR(80)     NOT NULL COMMENT 'income_regularity, expense_ratio ...',
  feature_value   DECIMAL(24,8)   NULL COMMENT '값(결측=NULL)',
  is_missing      BOOLEAN         NOT NULL DEFAULT FALSE COMMENT '결측 여부(0으로 대체 금지)',
  contribution    DECIMAL(18,8)   NULL COMMENT '로짓 기여도(설명용)',
  calculation_version VARCHAR(40) NOT NULL COMMENT '특성 계산 버전',
  PRIMARY KEY (id),
  CONSTRAINT uq_credit_feature_values UNIQUE (assessment_id, feature_code),
  CONSTRAINT fk_credit_feature_values_a FOREIGN KEY (assessment_id) REFERENCES credit_assessments (id) ON DELETE CASCADE,
  CONSTRAINT ck_credit_feature_values_missing CHECK ((is_missing = TRUE AND feature_value IS NULL) OR (is_missing = FALSE AND feature_value IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P1] 평가 입력 특성 스냅샷(국적·성별·나이 사용 금지)';

-- ---------------------------------------------------------------------
-- J. (P1) 환율 · 목표 환율 알림 · 해외송금 수취인 — 11/4 범위 확정(수출입은행 일별 고시 + 상용 API 시장 환율 참고값)
-- ---------------------------------------------------------------------

-- 환율 저장용 테이블 (수출입은행 일별 고시 + 상용 API 시장 환율 참고값, 스케줄러 1시간 주기 수집) --
CREATE TABLE exchange_rates (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  currency        CHAR(3)         NOT NULL COMMENT '통화(USD, VND 등)',
  unit_amount     INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '고시 단위(JPY·IDR·VND 100단위 등)',
  rate_date       DATE            NOT NULL COMMENT '기준일(KST). 기간 평균은 영업일별 마지막 rate_at 값으로 조회 시 계산(저장 안 함)',
  rate_at         DATETIME(3)     NOT NULL COMMENT '값의 기준 시각(UTC). KOREAEXIM은 시각 미제공이라 rate_date 00:00 KST로 저장(하루 1건), MARKET_REF는 원천 기준 시각(화면 "HH:mm 기준")',
  base_rate       DECIMAL(18,4)   NOT NULL COMMENT '매매기준율(KRW / unit_amount). MARKET_REF는 시장 환율 참고값',
  send_rate       DECIMAL(18,4)   NULL COMMENT '송금 보낼 때(TTS, KOREAEXIM만)',
  receive_rate    DECIMAL(18,4)   NULL COMMENT '송금 받을 때(TTB, KOREAEXIM만)',
  source          ENUM('KOREAEXIM','MARKET_REF','MOCK') NOT NULL COMMENT '출처(수출입은행 공식 고시 / 상용 API 시장 환율 참고값 / 데모)',
  provider        VARCHAR(50)     NULL COMMENT 'MARKET_REF 상용 API 이름(미확정, 예: fxfeed)',
  is_mock         BOOLEAN         NOT NULL DEFAULT FALSE COMMENT '데모 데이터 여부',
  fetched_at      DATETIME(3)     NOT NULL COMMENT '수집 시각(UTC, 장애 시 마지막 값 표시 판단용)',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  PRIMARY KEY (id),
  CONSTRAINT uq_exchange_rates_at UNIQUE (currency, source, rate_at),
  KEY ix_exchange_rates_cur_date (currency, rate_date, rate_at),
  CONSTRAINT ck_exchange_rates_base CHECK (base_rate > 0),
  CONSTRAINT ck_exchange_rates_mock CHECK (source <> 'MOCK' OR is_mock = TRUE),
  CONSTRAINT ck_exchange_rates_tt CHECK (source <> 'MARKET_REF' OR (send_rate IS NULL AND receive_rate IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 환율(평균·추이는 이 테이블에서 계산, LLM 생성 금지, "실시간" 표기 금지)';

-- 목표 환율 알림 설정 저장용 테이블 (P1, 앱 내 알림만. 카카오 알림톡·자동 예약 송금 없음) --
CREATE TABLE fx_alerts (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ID',
  user_id         BIGINT UNSIGNED NOT NULL COMMENT '사용자',
  currency        CHAR(3)         NOT NULL COMMENT '통화',
  rate_type       ENUM('BASE','SEND') NOT NULL DEFAULT 'SEND' COMMENT '비교할 환율 종류',
  comparator      ENUM('LTE','GTE') NOT NULL DEFAULT 'LTE' COMMENT '이하/이상일 때 알림',
  target_rate     DECIMAL(18,4)   NOT NULL COMMENT '목표 환율(exchange_rates.unit_amount 기준)',
  status          ENUM('ACTIVE','TRIGGERED','CANCELLED') NOT NULL DEFAULT 'ACTIVE' COMMENT '상태',
  triggered_rate_id BIGINT UNSIGNED NULL COMMENT '도달 판정에 쓴 환율 행',
  triggered_at    DATETIME(3)     NULL COMMENT '도달 시각',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정',
  PRIMARY KEY (id),
  KEY ix_fx_alerts_active (status, currency),
  KEY ix_fx_alerts_user (user_id, status),
  CONSTRAINT fk_fx_alerts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_fx_alerts_rate FOREIGN KEY (triggered_rate_id) REFERENCES exchange_rates (id),
  CONSTRAINT ck_fx_alerts_target CHECK (target_rate > 0),
  CONSTRAINT ck_fx_alerts_trigger CHECK (status <> 'TRIGGERED' OR triggered_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P1] 목표 환율 알림(알림만, 자동 송금 없음)';

-- 해외송금 받는 분 저장용 테이블 (TR-001 송금 화면·챗봇 재질의용. 송금 실행은 하지 않고 SOL 송금 화면으로 넘김) --
CREATE TABLE remittance_recipients (
  id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '수취인 ID',
  user_id                BIGINT UNSIGNED NOT NULL COMMENT '송금하는 사용자',
  external_recipient_key VARCHAR(100)    NOT NULL COMMENT 'SOL 등록 수취인 식별값(Mock은 고정 ID)',
  name_en                VARCHAR(200)    NOT NULL COMMENT '받는 분 영문 이름(해외송금 필수, 거래내역 counterparty_name과 같은 표기)',
  relationship           ENUM('MOTHER','FATHER','SPOUSE','CHILD','SIBLING','SELF','OTHER') NULL COMMENT '관계(사용자가 확인한 값만. 거래내역으로 추정해 저장하지 않음)',
  country_code           CHAR(2)         NOT NULL COMMENT '받는 나라(ISO 3166-1 alpha-2, 예: VN)',
  currency               CHAR(3)         NOT NULL COMMENT '받는 통화(예: VND)',
  bank_name              VARCHAR(100)    NOT NULL COMMENT '받는 은행 이름(예: Vietcombank)',
  swift_code             VARCHAR(11)     NULL COMMENT '받는 은행 SWIFT(BIC) 코드(8자리 또는 11자리)',
  account_no_masked      VARCHAR(50)     NOT NULL COMMENT '화면 표시용 마스킹 계좌번호(원문 저장 안 함)',
  source                 ENUM('MOCK','SOL','USER') NOT NULL DEFAULT 'MOCK' COMMENT '출처(Mock / SOL 등록 수취인 / 사용자 입력)',
  is_mock                BOOLEAN         NOT NULL DEFAULT TRUE COMMENT 'Mock 여부',
  is_active              BOOLEAN         NOT NULL DEFAULT TRUE COMMENT '사용 여부',
  relationship_confirmed_at DATETIME(3)  NULL COMMENT '사용자가 관계를 확인한 시각(재질의 응답)',
  created_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성',
  updated_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정',
  PRIMARY KEY (id),
  CONSTRAINT uq_remittance_recipients_ext UNIQUE (user_id, external_recipient_key),
  KEY ix_remittance_recipients_user (user_id, is_active),
  CONSTRAINT fk_remittance_recipients_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT ck_remittance_recipients_swift CHECK (swift_code IS NULL OR CHAR_LENGTH(swift_code) IN (8, 11)),
  CONSTRAINT ck_remittance_recipients_mock CHECK (source <> 'MOCK' OR is_mock = TRUE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='[P0] 해외송금 받는 분(Mock 시드 1건, 송금 실행 없음)';
