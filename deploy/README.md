# 운영 배포 (서버 1대 + Docker Compose)

서버 1대(Ubuntu, x86, 16GB)에 MySQL·RabbitMQ·MongoDB·Spring·AI 서버·AI Worker·OCR Worker·Caddy를 `deploy/compose.prod.yml` 하나로 띄운다.

- 외부에 여는 포트는 Caddy의 80·443뿐이다. 나머지 서비스는 compose 내부 네트워크에서 서비스 이름(`mysql`, `rabbitmq`, `mongodb`, `spring`, `ai-server`)으로 통신한다.
- `ai-worker`는 `ai-server`와 같은 이미지(`hanstep-ai:prod`)로 `python -m app.worker`를 실행한다. HTTP 포트 없이 RabbitMQ `policy.ingest` 큐를 소비해 정책 문서를 적재하고 Spring 내부 API로 상태를 보고한다. 1개만 띄운다.
- `ocr-worker`는 hanstep-AI `ocr/`로 만든 별도 이미지(`hanstep-ocr:prod`, Python 3.10 + paddle, 약 2.4GB)로 `python -m worker`를 실행한다. HTTP 포트 없이 `ocr.request` 큐를 소비해 등록증 원본을 R2에서 읽고, OCR 결과를 Spring 내부 API로 보고한다. 1개만 띄우고 CPU를 2개로 제한한다. 자세한 내용은 [OCR Worker](#ocr-worker).
- Caddy는 `/api/v1/*`를 Spring으로 전달하고, `/internal/*`는 외부에서 404로 막는다.
- Swagger UI(`/swagger-ui/*`, `/v3/api-docs*`)는 Caddy 기본 인증을 통과해야 열린다. 운영 문서에는 `/api/v1`만 나온다(Spring `prod` 프로필, `application-prod.yaml`). 설정은 [Swagger UI 기본 인증](#swagger-ui-기본-인증).
- 비밀값은 `deploy/.env`에 둔다(커밋 금지). 키 목록과 설명은 `deploy/.env.example`.
- 로컬 개발용 `docker-compose.yml`(루트)과는 별개다.

아래 명령은 모두 서버의 `hanstep-backend` 루트에서 실행한다.

## 1. 서버 Docker 설치

Docker 공식 문서의 Ubuntu 설치 방법(apt 저장소)으로 Docker Engine과 Compose 플러그인을 설치한다.

```bash
sudo apt-get update && sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update && sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker $USER   # 다시 로그인한 뒤부터 sudo 없이 docker 사용
```

방화벽(클라우드 보안 그룹 포함)은 22(SSH), 80, 443만 연다.

## 2. 레포 2개를 나란히 clone

compose가 `../../hanstep-AI/ai`(AI 서버 빌드), `../../hanstep-AI/ocr`(OCR Worker 빌드), `../../hanstep-AI/data/rag_documents`(RAG 원문)를 상대 경로로 쓰므로 같은 폴더에 나란히 둔다. OCR Worker는 hanstep-AI 레포 안(`ocr/`)에 있어 따로 clone하지 않는다.

```bash
mkdir -p ~/hanstep && cd ~/hanstep
git clone https://github.com/hanstep-lab/hanstep-backend.git
git clone https://github.com/hanstep-lab/hanstep-AI.git
```

```
~/hanstep/
├── hanstep-backend/   ← 여기서 docker compose 실행
└── hanstep-AI/        ← ai/(AI 서버·AI Worker), ocr/(OCR Worker)
```

## 3. deploy/.env 작성

```bash
cd ~/hanstep/hanstep-backend
cp deploy/.env.example deploy/.env
chmod 600 deploy/.env
vi deploy/.env
```

- 비밀번호는 `openssl rand -hex 24`처럼 영문·숫자로 만든다(MongoDB·RabbitMQ 비밀번호가 URI에 그대로 들어감).
- `SPRING_INTERNAL_API_KEY`는 32자 이상이다. compose가 같은 값을 Spring, AI 서버·AI Worker, OCR Worker에 넣는다.
- `AI_INTERNAL_API_TOKEN`은 32자 이상이며 `SPRING_INTERNAL_API_KEY`와 다른 값으로 한다.
- `CHAT_LLM_MODE`를 비우면 `anthropic`(실제 호출, 유료)이라 `ANTHROPIC_API_KEY`가 필요하다. AI 코드 기본값은 `fake`(가짜 답변)다.
- `E74_INCLUDE_DRAFT_RULES`는 비워 둔다(false). 2026 규칙(`E74-2026-v1`)은 승인(APPROVED) 상태라 그대로 계산된다(#95).
- `ROADMAP_LLM_TEXT=true`면 로드맵 단계 문장을 LLM(`ANTHROPIC_API_KEY`, 모델 `claude-haiku-5-5`)으로 만든다. 비우면 TEMPLATE이다. 숫자·날짜는 엔진 값 그대로다. 생성(POST)할 때만 LLM을 1번 부르고(로컬 중앙값 3,083ms), 조회(GET)는 저장된 결과를 읽는다(11.0ms). **켠 뒤 생성(POST)을 해야 반영된다**. 템플릿 문장으로 저장된 로드맵은 그 POST 때 LLM 문장으로 바뀐다(쿨다운 5분, [로드맵 문장(LLM)](#로드맵-문장llm)).
- `R2_OCR_ACCESS_KEY_ID`·`R2_OCR_SECRET_ACCESS_KEY`는 외국인등록증 버킷(`R2_ARC_BUCKET`) 전용 쓰기 키다. Spring이 등록증 앞면 업로드(PutObject)와 확정·재업로드 시 원본 삭제(DeleteObject)에 쓴다(#73). 정책·상품 키(`R2_ACCESS_KEY_ID`)와 다른 키이고, 이 버킷의 읽기·쓰기·삭제 권한만 준다. 비어 있으면 업로드가 500으로 실패한다.
- `R2_OCR_WORKER_ACCESS_KEY_ID`·`R2_OCR_WORKER_SECRET_ACCESS_KEY`는 같은 버킷의 **읽기 전용** 키다. OCR Worker가 원본을 읽는 데(GetObject)만 쓰고, compose가 Worker에는 `R2_OCR_ACCESS_KEY_ID`·`R2_OCR_SECRET_ACCESS_KEY` 이름으로 넘긴다. 위 Spring 키와 다른 키로 만든다. 비어 있으면 OCR Worker만 종료하고(다른 서비스는 그대로 뜸), 올린 등록증은 확인 대기로 넘어가지 않는다.
- Spring이 처음 RabbitMQ에 연결할 때 `ocr.request`·`ocr.request.retry`·`ocr.request.dlq` 큐를 선언한다(#73). `ocr-worker`가 이 큐를 소비한다. Worker가 떠 있지 않은 동안 올린 등록증의 OCR 작업은 `ocr.request`에 쌓였다가 Worker가 뜨면 처리된다.
- 발행 대기 AI 작업(큐에 못 들어간 OCR·정책 적재 작업)은 Spring이 1분마다 다시 보낸다. 끄려면 `AI_JOB_REPUBLISH_ENABLED=false`를 spring 서비스 환경 변수에 넣는다.
- `KOREAEXIM_API_KEY`는 환율 수집기의 한국수출입은행 인증키다. `FX_COLLECTOR_ENABLED`는 처음에는 비워 둔다(false). 켜는 순서는 [환율 수집기](#환율-수집기).
- MySQL·RabbitMQ·MongoDB 계정은 볼륨이 비어 있는 최초 기동 때만 적용된다.
- `SWAGGER_BASIC_USER`·`SWAGGER_BASIC_HASH`는 필수다. 만드는 법은 [Swagger UI 기본 인증](#swagger-ui-기본-인증).

문법 확인(값은 화면에 펼쳐지므로 공유하지 않는다):

```bash
docker compose -f deploy/compose.prod.yml config --quiet && echo OK
```

## 4. 빌드와 기동

```bash
docker compose -f deploy/compose.prod.yml up -d --build
docker compose -f deploy/compose.prod.yml ps
```

- 최초 기동 때 MySQL이 `db/init`(스키마 → 기준 시드 → 데모 시드)를 실행한다. 로컬 전용 시드(`db/dev`, Mock 환율)는 운영에 마운트하지 않는다(운영 환율은 실데이터만).
- **이미 떠 있는 DB**(볼륨 있음)에는 `db/init` 변경이 들어가지 않는다. 그런 변경은 `deploy/migrations/`에 날짜별 SQL로 두고, `git pull` 뒤 `up` 전에 순서대로 실행한다. 빠뜨리면 Spring이 스키마 검증(`ddl-auto: validate`)에서 기동에 실패할 수 있다. 여러 번 실행해도 결과가 같게 만든다.
  - `deploy/migrations/manual/`의 SQL은 자동 배포가 실행하지 않는다(정해진 때 사람이 실행). 지금은 Mock 환율 정리 SQL 하나다([환율 수집기](#환율-수집기)).
  - `2026-11-17-bill-payments-e74-approved.sql`: 청구 납부 테이블·Reyes 납부 내역(#87), E-7-4 2026 규칙 승인(#95). 실행 명령과 확인 쿼리는 파일 맨 위·맨 아래에 있다.
  - `2026-11-17-policy-documents-approved.sql`: 비자 정책 문서 1~6번 승인과 이력(#134, 관리자 계정이 없어 운영 DB에 직접 실행한 SQL 기록). DRAFT인 행만 바꾸므로 운영에서는 다시 실행해도 그대로다. MySQL만 바꾸며, MongoDB 청크는 파일 아래쪽 명령으로 AI `reconcile_policy_state.py`를 따로 실행한다.
- AI 서버는 `LOAD_MODEL_ON_STARTUP=true`라 처음에 BGE-M3(~2.3GB)를 받아 `hf-cache` 볼륨에 저장한다. 처음에는 몇 분 걸린다.
- OCR Worker 이미지는 첫 빌드가 오래 걸린다(paddle 설치·OCR 모델 다운로드). [OCR Worker](#ocr-worker)의 "첫 빌드"를 먼저 본다.
- Caddy는 Spring이 healthy가 된 뒤 뜨고, `DOMAIN`의 인증서를 자동 발급한다. 확인: `curl https://<도메인>/api/v1/health`

## 자동 배포

develop에 머지(push)되면 GitHub Actions(`deploy-prod`)가 서버에 SSH로 접속해 `deploy/auto-deploy.sh`를 실행한다. BE·AI 어느 레포에 머지돼도 두 레포를 모두 최신으로 받아 함께 배포한다.

### 순서

1. `flock`으로 잠근다. BE·AI가 동시에 머지되면 하나씩 실행된다.
2. `~/hanstep/AUTO_DEPLOY_OFF`가 있으면 `skipped`를 출력하고 끝낸다.
3. 두 레포가 develop인지 확인하고 `git pull --ff-only`로 받는다.
4. `deploy/migrations/*.sql`을 이름순으로 실행한다.
5. `config --quiet` → `build` → `up -d` 순서로 실행한다.
6. `https://hanstep-shinhan.duckdns.org/api/v1/health`가 2분 안에 `{"status":"UP"}`가 되는지 확인한다.

3~5에서 실패하면 그 자리에서 멈추고 Actions가 빨간불이 된다. 빌드 단계에서 실패하면 기존 컨테이너는 그대로 동작한다. 6에서 실패하면 이미 새 컨테이너로 바뀐 상태이므로 서버에서 로그를 확인한다.

### 자동 배포 끄기·켜기

```bash
touch ~/hanstep/AUTO_DEPLOY_OFF   # 끄기 (발표 당일·리허설)
rm ~/hanstep/AUTO_DEPLOY_OFF      # 켜기
```

### 로그

- 서버: `~/hanstep/deploy-logs/YYYYMMDD-HHMMSS.log` (서버 시간 UTC 기준). 첫 줄에 호출한 레포와 커밋이 남는다.
- Actions: 각 레포 Actions 탭의 `deploy-prod` 실행 기록

### 주의

- 배포할 때마다 Spring·AI가 다시 빌드되어 30초~1분 끊길 수 있다. 머지 직후 FE 테스트가 실패하면 이 때문일 수 있다.
- Actions 제한 시간은 20분이다. OCR Worker 첫 빌드처럼 오래 걸리는 빌드는 머지 전에 서버에서 미리 해 둔다([OCR Worker](#ocr-worker)).
- EC2가 꺼진 시간에 머지하면 Actions가 실패한다. 서버를 켠 뒤 해당 실행에서 Re-run을 누른다.
- 마이그레이션 파일은 여러 번 실행해도 결과가 같게 작성한다.
- 자동 실행되면 안 되는 SQL(데이터 삭제 등)은 `deploy/migrations/manual/`에 둔다. 자동 배포는 `deploy/migrations` 바로 아래 `*.sql`만 실행한다.
- `docker compose down -v`는 쓰지 않는다. DB 볼륨이 지워진다.
- `docker compose config`는 `--quiet` 없이 실행하지 않는다. `.env` 값이 화면과 로그에 나온다.

### 배포 키

- Actions는 배포 전용 키로 접속한다. 서버 `~/.ssh/authorized_keys`에 `restrict,command=".../deploy/auto-deploy.sh"`로 등록되어 있어서, 이 키로는 셸이 열리지 않고 스크립트만 실행된다.
- Secrets(BE·AI 레포 각각): `DEPLOY_SSH_KEY`, `DEPLOY_HOST`, `DEPLOY_KNOWN_HOSTS`
- 키 값은 Secrets에만 둔다. 노션·단체방·코드에 올리지 않는다.

## 5. AI 준비

```bash
alias dc='docker compose -f deploy/compose.prod.yml'
```

1. 내부 키 확인: 두 값이 모두 `True`여야 한다(키 값은 출력하지 않음).

   ```bash
   dc exec ai-server python -c "from app.core.config import get_settings as g; s = g(); print('spring', s.spring_configured(), 'internal', s.internal_token_configured())"
   ```

2. MongoDB 인덱스 만들기:

   ```bash
   dc exec ai-server python scripts/create_atlas_indexes.py --apply
   ```

3. RAG 문서 적재. manifest 경로는 **지수 확인** 후 채운다. `--apply`가 쓰는 Spring 내부 API 경로도 **지수 확인 필요**.

   ```bash
   dc exec ai-server python scripts/ingest_rag_documents.py --manifest <지수 확인> --apply
   ```

4. 준비 확인: `/health/ready`가 200이어야 한다.

   ```bash
   dc exec ai-server python -c "import urllib.request as u; print(u.urlopen('http://127.0.0.1:8000/health/ready', timeout=5).status)"
   ```

5. Worker 확인: `ai-worker`는 `ai-server`가 healthy가 된 뒤 뜬다. 2번 인덱스가 없으면 시작하다 종료하고 재시작을 반복하므로 인덱스를 만든 뒤 본다. `dc logs ai-worker`에 `embedding model BAAI/bge-m3 loaded=True`와 `consuming policy.ingest`가 찍히고, `dc exec rabbitmq rabbitmqctl list_queues name consumers`에서 `policy.ingest`의 consumers가 1이어야 한다.

### 로드맵 문장(LLM)

`ROADMAP_LLM_TEXT`는 ai-server 컨테이너를 다시 만들어야 적용된다. 조회(GET)는 저장된 결과를 읽으므로 켠 뒤 **생성(POST)을 해야** LLM 문장이 들어간다. 템플릿 문장으로 저장된 로드맵은 POST 때 Spring이 AI를 다시 불러, LLM 문장이 오면 같은 로드맵(`roadmapId` 그대로)을 바꾼다(#139). 다시 부르는 것은 로드맵마다 쿨다운(`hanstep.roadmap.llm-retry-cooldown`, 기본 5분)에 한 번이다. 이미 LLM 문장인 로드맵은 AI를 부르지 않는다.

1. `deploy/.env`에 `ROADMAP_LLM_TEXT=true`를 넣고 ai-server만 다시 만든다. BGE-M3를 다시 올리는 동안 챗봇·로드맵 호출이 잠깐 실패할 수 있다.

   ```bash
   dc up -d ai-server
   dc exec ai-server python -c "from app.core.config import get_settings as g; s = g(); print('roadmap_llm', s.roadmap_llm_text, 'anthropic', s.anthropic_configured(), 'model', s.roadmap_text_model_id)"
   ```

   `roadmap_llm True anthropic True model claude-haiku-5-5`가 나와야 한다.

2. Reyes 로드맵을 생성(POST)한다. 데모 계정 초기화(`POST /api/v1/demo/reset`, #132)는 로드맵을 지우지도 다시 만들지도 않는다. 저장된 템플릿 로드맵은 이 POST로 바뀐다.

   ```bash
   TOKEN=$(curl -fsS -X POST https://<도메인>/api/v1/auth/entry -H 'Content-Type: application/json' \
     -d '{"entryToken":"demo-e9-ph-reyes"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
   curl -fsS -X POST -H "Authorization: Bearer $TOKEN" https://<도메인>/api/v1/visa/roadmaps | grep -o '"reused":[a-z]*\|"text":{[^}]*}'
   ```

   `"reused":false`와 `"text":{"source":"LLM",...}`가 나와야 한다. 템플릿 로드맵을 바꾼 것이면 응답이 201이고 `roadmapId`는 그대로다. 앱의 조회(`GET /api/v1/visa/roadmaps/latest`)도 바뀐 문장을 준다.

   - `"reused":true`이고 `"source":"TEMPLATE"`이면 쿨다운 안이다. 그 로드맵으로 AI를 부른 지 5분이 지나지 않았다는 뜻이고, 템플릿으로 새로 만든 직후도 여기에 든다. 5분 뒤 다시 POST한다. Spring을 재시작하면 기록이 사라져 바로 다시 시도한다.
   - 쿨다운이 지나 다시 POST해도 TEMPLATE이면 AI가 LLM 문장을 만들지 못한 것이다. 1번 설정을 다시 확인하고, LLM 호출 실패(15초 초과·키·한도 등)인지 AI 로그를 본다. 템플릿은 저장된 것 그대로 남고, 다음 쿨다운 뒤 POST 때 다시 시도한다.

     ```bash
     dc logs --since 10m ai-server | grep "roadmap text LLM"
     ```

## OCR Worker

`ocr-worker`(hanstep-AI `ocr/worker`, AI #80)는 등록증 OCR을 처리한다. 흐름: 등록증 업로드(Spring, 202) → `ocr.request` 큐 → Worker가 Spring 내부 API로 원본 위치를 받아 R2에서 원본을 읽음 → OCR → Spring에 결과 보고 → 문서가 확인 대기(`PENDING_CONFIRMATION`)로 바뀐다.

### 필요한 .env 키

| 키 | 용도 |
|---|---|
| `RABBITMQ_DEFAULT_USER`·`RABBITMQ_DEFAULT_PASS` | compose가 Worker의 `RABBITMQ_URL`(`rabbitmq:5672`)을 만든다 |
| `SPRING_INTERNAL_API_KEY` | Worker → Spring 내부 API 인증(`X-Internal-Token`). Spring과 같은 값 |
| `R2_ENDPOINT_URL`·`R2_REGION` | Spring·AI 서버와 같은 값 |
| `R2_ARC_BUCKET` | 등록증 버킷 이름. 비우면 `hanstep-arc-private` |
| `R2_OCR_WORKER_ACCESS_KEY_ID`·`R2_OCR_WORKER_SECRET_ACCESS_KEY` | 등록증 버킷 **읽기 전용** 키. compose가 Worker에는 `R2_OCR_ACCESS_KEY_ID`·`R2_OCR_SECRET_ACCESS_KEY` 이름으로 넘긴다. Spring의 `R2_OCR_*`(쓰기·삭제)와 다른 키 |

- `SPRING_INTERNAL_API_URL`(`http://spring:8080`)과 `WORKER_ID`(`ocr-worker-1`)는 compose에 고정돼 있다.
- Worker에는 정책·상품 버킷 키(`R2_ACCESS_KEY_ID`)와 MongoDB 주소를 넣지 않는다(`x-ai-base-env`를 상속하지 않음).
- 필수 값이 비었거나 예시값이면 Worker가 모델을 올리기 전에 `worker not configured: ...`를 남기고 종료한다. 이때 다른 서비스는 그대로 뜬다. `.env`를 고친 뒤 `dc up -d ocr-worker`.

### 첫 빌드

- 이미지(약 2.4GB)를 빌드할 때 paddle을 설치하고 OCR 모델을 받아 넣으므로 첫 빌드가 오래 걸린다. 그다음부터는 `ocr/worker` 코드만 바뀌면 캐시를 써서 빠르다(Dockerfile이 모델 층 뒤에 Worker 코드를 둔다).
- 자동 배포 Actions의 제한 시간은 20분(`timeout-minutes: 20`)이라 첫 빌드가 그 안에 끝나지 않을 수 있다. ocr-worker가 처음 들어가는 배포 **전에** 서버에서 한 번 빌드해 캐시를 만들어 둔다. 그러면 자동 배포의 `build`가 캐시를 쓴다.

  ```bash
  git -C ~/hanstep/hanstep-AI pull --ff-only origin develop
  docker build -t hanstep-ocr:prod ~/hanstep/hanstep-AI/ocr
  ```

### 확인

- `dc logs ocr-worker`에 `ocr model ppocrv5-ko-v0lite loaded in …s`와 `worker ocr-worker-1 consuming ocr.request`가 찍힌다.
- `dc exec rabbitmq rabbitmqctl list_queues name consumers messages`에서 `ocr.request`의 consumers가 1이다.
- 문서 1건 처리 시간과 단계별 시간은 `dc logs ocr-worker`의 `ocr reported modelVersion=… latencyMs=… timings=…` 줄에 나온다.

### 시연 전 예열

배포 직후(Worker 컨테이너가 새로 뜬 뒤) 첫 OCR은 느리다. 시연 전에 합성 견본 이미지(실제 등록증 아님)를 한 번 올려 확인 대기까지 가는지 보고 예열해 둔다.

1. Swagger에서 `POST /api/v1/auth/entry`로 토큰을 받는다.
2. `POST /api/v1/documents`(multipart `front`)에 견본 이미지를 올린다 → 202
3. `GET /api/v1/documents/{id}`의 `status`가 `PENDING_CONFIRMATION`이 되는지 본다.
4. 확정(`POST /api/v1/documents/{id}/confirm`)은 하지 않는다. 확정하면 그 사용자의 체류 프로필이 바뀐다.

### CPU

- `cpus: "2"`는 임시값이다. 로컬 측정에서 OCR 추론이 CPU 8코어를 모두 써서 같은 서버의 Spring·AI 서버(챗봇)가 느려질 수 있었다. 서버에서 `nproc`를 확인하고 OCR 처리 시간과 챗봇 응답 시간을 같이 재서 조정한다.
- 값이 서버 코어 수보다 크면 컨테이너가 뜨지 않는다. 서버 코어가 2개면 Worker가 CPU를 전부 쓸 수 있으므로 1로 낮춘다.

## 환율 수집기

화면·챗봇은 `exchange_rates`만 읽는다. 외부 환율 API를 부르는 곳은 Spring의 환율 수집기 하나뿐이다(#119). 환율은 실데이터만 두고, 못 받은 날짜는 비워 둔다(화면·챗봇이 "데이터 부족"으로 처리).

| 원천 | 통화 | `source` | 저장하는 값 |
|---|---|---|---|
| 한국수출입은행 현재환율 Open API | USD(설정으로 23개 고시 통화까지) | `KOREAEXIM` | 매매기준율·송금 보낼 때·받을 때. 기준일 00:00 KST로 하루 1건 |
| Frankfurter v2, 기관 ECB 고정 | PHP | `MARKET_REF`(`provider=frankfurter-ecb`) | 매매기준율만. `rate_at`은 ECB 고시 시각(프랑크푸르트 16:00) |

### 필요한 .env 키

| 키 | 용도 |
|---|---|
| `KOREAEXIM_API_KEY` | 수출입은행 인증키. 없으면 수출입은행 수집·백필만 실패한다(로그 `KOREAEXIM_API_KEY is not set`) |
| `FX_COLLECTOR_ENABLED` | 1시간 주기 수집. 비우면 false. 백필은 이 값과 관계없이 된다 |
| `FX_COLLECTOR_CURRENCIES` | 비우면 `USD,PHP` |
| `FRANKFURTER_BASE_URL` | 비우면 `https://api.frankfurter.dev/v2` |

키는 `deploy/.env`에만 둔다. 요청 URL은 로그에 남기지 않고, 실패 이유의 `authkey=` 값은 가린다.

### 처음 켜는 순서(운영 Mock 환율 → 실데이터)

운영 DB에는 04 Mock 환율 738행(USD·PHP·VND 각 246, 2025-11-07 ~ 2026-11-06)이 있다(11/17 확인). 2·3을 연달아 실행한다. 그 사이(몇 분) 송금 견적·챗봇 환율·목표 환율 알림 만들기는 `FX_RATE_NOT_FOUND`다.

1. `deploy/.env`에 `KOREAEXIM_API_KEY`를 넣고 `FX_COLLECTOR_ENABLED`는 비운 채 배포한다. 이미 떠 있으면 `dc up -d spring`으로 키를 반영한다.
2. Mock 환율 정리. 맨 위 확인 쿼리에서 `alerts_on_mock_rates`가 0이어야 지워진다(0이 아니면 외래 키 오류로 아무것도 지워지지 않는다).

   ```bash
   dc exec -T mysql sh -c 'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < deploy/migrations/manual/2026-11-17-delete-mock-exchange-rates.sql
   ```

3. 백필(기본: 오늘(KST)부터 12개월). 끝날 때까지 응답을 기다린다. 내부 키는 컨테이너 환경 변수를 쓰므로 명령에 펼쳐지지 않는다.

   ```bash
   dc exec spring sh -c 'curl -fsS -X POST -H "X-Internal-Token: $SPRING_INTERNAL_API_KEY" "http://127.0.0.1:8080/internal/v1/fx/backfill"'
   ```

   - 수출입은행은 평일마다 1번(12개월이면 약 260번), Frankfurter는 1번 부른다. 수출입은행 호출 사이에 300ms를 쉰다.
   - 응답의 `sources[].status`가 `OK`이고 `failures`가 비었는지 본다. `nextFrom`이 있으면 그 날짜를 `from`으로 다시 부른다(1회 호출 상한 400, 인증키 오류 `result=3`, 일일 한도 `result=4`에서 멈춤).
   - 다시 실행해도 같은 값은 새 행을 만들지 않고 `fetched_at`도 고치지 않는다(`unchanged`로 센다).
   - 나눠 돌리기: `?from=2025-11-17&to=2026-05-17`, 한 원천만 `&source=KOREAEXIM`(또는 `MARKET_REF`), 호출 상한 `&maxCalls=200`. 수출입은행 일일 한도는 1000회로 알려져 있으나 확인되지 않았다.

4. 확인: `is_mock`이 모두 0이고 USD는 `KOREAEXIM`, PHP는 `MARKET_REF`다.

   ```bash
   dc exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' <<'SQL'
   SELECT source, is_mock, currency, COUNT(*) AS cnt, MIN(rate_date) AS first_date, MAX(rate_date) AS last_date
   FROM exchange_rates GROUP BY source, is_mock, currency ORDER BY source, currency;
   SQL
   ```

5. `deploy/.env`에 `FX_COLLECTOR_ENABLED=true`를 넣고 `dc up -d spring`. 기동 1분 뒤부터 1시간마다 수출입은행(오늘 고시)과 Frankfurter(최신 ECB 기준일)를 받는다. 수출입은행은 영업일 11시 무렵 고시하므로 그 전·주말·공휴일의 빈 응답은 실패가 아니다.

### 로그

`dc logs spring | grep 'fx '`

- 실패: `fx collect failed: source=KOREAEXIM currencies=[USD] date=2026-11-17 reason=HTTP 503`처럼 원천마다 WARN 한 줄. 한 원천이 실패해도 다른 원천은 저장한다.
- 저장: `fx collect saved: source=MARKET_REF backfill=false rows=1`. 같은 값만 받았으면 로그가 없다.

### fetched_at(마지막 확인 시각)

`exchange_rates.fetched_at`은 그 값을 원천에서 마지막으로 확인한 시각이다. 환율 화면의 "업데이트 확인" 시각과 PHP(MARKET_REF)의 stale 판정(3시간)에 쓴다(#123).

- 1시간 수집에서 같은 `rate_date`·같은 값을 다시 받으면, 새 행 없이 그 최신 행의 `fetched_at`만 지금 시각으로 고친다(USD·PHP 같음).
- 값이 바뀌었으면 값과 `fetched_at`을 고친다. 같은 키의 Mock 행은 건드리지 않는다.
- 백필은 같은 값이면 아무것도 쓰지 않는다(새 행 0, `fetched_at` 변경 0).
- 확인: 수집기가 켜져 있으면 1시간마다 두 통화 최신 행의 `fetched_at`이 바뀐다. 1시간 넘게 지나서 다시 보면 `fetched_at`이 앞의 결과보다 늦어야 한다.

  ```bash
  dc exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' <<'SQL'
  SELECT e.currency, e.source, e.rate_date, e.fetched_at, UTC_TIMESTAMP(3) AS now_utc
  FROM exchange_rates e
  JOIN (SELECT currency, source, MAX(rate_at) AS rate_at FROM exchange_rates WHERE NOT is_mock GROUP BY currency, source) l
    ON l.currency = e.currency AND l.source = e.source AND l.rate_at = e.rate_at
  ORDER BY e.currency;
  SQL
  ```
- 저장하지 않은 값(숫자 아님·0 이하·소수 5자리 이상): `fx rate rejected: ...`

### 로컬을 운영과 같게 만들기

로컬 DB에는 04 Mock 환율(`db/dev`)이 있다. 수집기는 같은 키의 Mock 행을 덮어쓰지 않으므로, 그대로 수집하면 11/06 이전 USD는 Mock 행(`source=KOREAEXIM`, `is_mock=1`)으로 남아 실데이터와 섞인다. 실데이터만 두려면 아래를 순서대로 한다(레포 루트, Git Bash).

1. 정리 SQL(Mock 738행 삭제. VND 는 수집 대상이 아니라 이후 `FX_RATE_NOT_FOUND`):

   ```bash
   docker exec -i hanstep-mysql sh -c 'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < deploy/migrations/manual/2026-11-17-delete-mock-exchange-rates.sql
   ```

2. 로컬 Spring 을 띄우고 백필:

   ```bash
   K=$(grep '^SPRING_INTERNAL_API_KEY=' .env | cut -d= -f2-)
   curl -fsS -X POST -H "X-Internal-Token: $K" http://localhost:8080/internal/v1/fx/backfill
   ```

- 백필은 하루 1번만 돌린다. 12개월이면 수출입은행을 약 260번 부른다(일일 한도 1000회로 알려져 있으나 미확인. 같은 키를 쓰는 다른 환경과 한도를 나눠 쓴다).
- 테스트는 04 시드에 기대지 않아(환율 행을 테스트 안에서 넣음) 정리한 뒤에도 그대로 통과한다.

## 목표 환율 알림 도달 판정

수집기가 새 환율을 저장하면(새 행이거나 값이 바뀐 행) 같은 스레드에서 그 통화의 ACTIVE 알림을 판정한다(#125). 따로 켤 설정은 없다. 원격 푸시는 없다. 알림을 `TRIGGERED`로 바꾸면 그 사용자의 열린 스트림으로 바로 보내고(아래 "도달 알림 스트림"), 앱 알림 목록(`GET /api/v1/fx/alerts`)도 `TRIGGERED`와 `triggeredRate`를 보여 준다.

- 비교 값은 `rate_type`이 SEND면 `send_rate`, BASE면 `base_rate`다. PHP(MARKET_REF)는 `send_rate`가 없어 BASE 알림만 닿는다.
- 알림을 만든 뒤 처음 저장된 환율 행(`exchange_rates.created_at` > 알림 `created_at`)만 본다. 오전 10시에 만든 USD 알림은 그날 11시 무렵 처음 저장되는 고시값부터 판정한다. 이미 있던 행은 `fetched_at`(마지막 확인 시각)이 갱신되거나 값이 바뀌어도 보지 않는다.
- Mock 행(`is_mock = TRUE`)으로는 판정하지 않는다.
- 로그: `fx alert triggered: alertId=... currency=PHP rateId=... value=... by=collect` (백필이면 `by=backfill`)

### 다시 판정(시연·관리용)

현재 최신 실데이터 값(우선 원천 KOREAEXIM > MARKET_REF)으로 ACTIVE 알림을 다시 판정한다. 기본(`ignoreCreatedAt=false`)은 저장 때와 같은 규칙이라, 알림보다 먼저 저장된 최신 값으로는 바뀌지 않는다(`RATE_BEFORE_CREATED`).

`ignoreCreatedAt=true`는 **시연에서 도달 장면을 보여 줄 때만 사용**한다. "알림 뒤에 저장된 행" 규칙을 빼고 지금 값으로 판정한다(실데이터만, 조건부 UPDATE는 같다).

```bash
dc exec spring sh -c 'curl -fsS -X POST -H "X-Internal-Token: $SPRING_INTERNAL_API_KEY" "http://127.0.0.1:8080/internal/v1/fx/alerts/evaluate?currency=PHP&ignoreCreatedAt=true"'
```

- `currency`를 빼면 ACTIVE 알림이 있는 통화를 모두 본다.
- 응답의 `alerts[].result`는 알림마다 `TRIGGERED`·`NOT_REACHED`·`NO_RATE_VALUE`·`RATE_BEFORE_CREATED`·`NO_RATE`·`ALREADY_CHANGED` 중 하나다.
- 외부(Caddy)에서는 `/internal`이 막혀 있다.

### 도달 알림 스트림

앱이 켜져 있는 동안 `GET /api/v1/fx/alerts/stream`(SSE)을 열어 두면, 판정이 알림을 `TRIGGERED`로 바꾼 직후 서버가 이벤트를 보낸다(#129). 앱은 받으면 로컬 알림을 띄우고 `POST /api/v1/fx/alerts/{alertId}/notified`를 보낸다. 앱이 꺼져 있으면 오지 않는다(실서비스에서는 호스트 앱 SOL 푸시 채널 몫).

- 연결하면 `event:ready`, 이어서 아직 알리지 않은 도달(`TRIGGERED`이고 `notified_at`이 비어 있음)을 `event:fx-alert-triggered`로 먼저 보낸다(보충). 서버는 보낸 것만으로 알렸다고 보지 않는다. 앱이 notified를 보내야 `notified_at`이 채워지고 다음 연결부터 빠진다.
- 연결 목록은 Spring 메모리에 있다. **재배포하면 연결이 모두 끊기고**(종료가 시작될 때 서버가 먼저 닫아 graceful shutdown 이 기다리지 않는다), 앱이 다시 연결하면 그사이 도달은 보충으로 받는다.
- 25초마다 `: ping` 주석, 연결 1개는 30분이 지나면 서버가 닫는다(둘 다 `hanstep.fx-alert-stream.*` 임시값). 한 사용자 연결은 3개까지이고 넘으면 가장 오래된 연결을 닫는다.
- 앱이 끊은 연결은 다음 ping을 쓸 때 드러나 목록에서 빠진다. 끊긴 직후 첫 쓰기는 성공할 수 있어 최대 ping 2번(약 50초) 걸린다.
- Caddy는 챗봇 스트림과 같은 `handle /api/v1/*`로 전달하고 따로 예외를 두지 않는다. 사이트 전체 `encode gzip`이 스트림에도 걸리지만(앱이 `Accept-Encoding: gzip`을 보낼 때) 이벤트마다 바로 흘려보낸다(로컬 Caddy 2.11.9 확인, 판정 → 수신 15~50 ms, 3회).
- 로그(값·목표는 남기지 않음):
  - `fx alert stream opened userId=1 connectionId=4 userConnections=1 totalConnections=1`
  - `fx alert stream sent alertId=181 userId=1 connections=1 sent=1` (연결 중 도달) / `fx alert stream backfill sent userId=1 connectionId=2 alerts=3` (연결 시 보충)
  - `fx alert stream closed userId=1 connectionId=4 reason=TIMEOUT openSeconds=60 totalConnections=0` (타임아웃을 60초로 띄운 로컬). reason은 `COMPLETED`·`TIMEOUT`·`ERROR`·`SEND_FAILED`(끊긴 연결에 쓰다 실패)·`REPLACED`(사용자당 3개 초과)·`BACKFILL_FAILED`·`SHUTDOWN`

```bash
dc logs --since 10m spring | grep "fx alert stream"
```

## 6. 데모 데이터

내 PC(Windows)의 `hanstep-backend`에서 실행한다.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\demo-setup.ps1 -BaseUrl https://<도메인>
```

마지막 줄의 여유자금이 **675,317 원**인지 확인한다.

### 목표 환율 알림 도달 장면

**시연에서 도달 장면을 보여 줄 때만 사용한다.** 평소 도달 판정은 수집기가 새 환율을 저장할 때 자동으로 하고, 알림보다 먼저 저장된 값으로는 바뀌지 않는다("목표 환율 알림 도달 판정").

1. 앱에서 Reyes로 PHP 알림을 만든다. PHP는 송금 환율이 없어 기준환율(BASE) 알림만 닿는다. 예: BASE·이상(GTE)·목표는 현재 환율보다 조금 낮게.
2. 앱을 켜 둔다(앱이 스트림을 연결한다). 서버 로그에 `fx alert stream opened userId=...`가 보이면 된다.

   ```bash
   dc logs --since 10m spring | grep "fx alert stream"
   ```

3. 서버에서 현재 최신 실데이터 값으로 다시 판정한다(`ignoreCreatedAt=true`).

   ```bash
   dc exec spring sh -c 'curl -fsS -X POST -H "X-Internal-Token: $SPRING_INTERNAL_API_KEY" "http://127.0.0.1:8080/internal/v1/fx/alerts/evaluate?currency=PHP&ignoreCreatedAt=true"'
   ```

4. 응답의 `currencies[0].alerts[].result`가 `TRIGGERED`면 바로 앱에 로컬 알림이 뜬다(로그 `fx alert stream sent alertId=... sent=1`). 앱은 알림을 띄운 뒤 notified를 보내고, 알림 목록의 그 알림에 `notifiedAt`이 채워진다. 목표에 닿지 않았으면 `NOT_REACHED`다.
   - 앱이 꺼져 있었으면 다음에 앱을 열 때(스트림 연결) 같은 알림이 뜬다.

#### 반복 시연

- 판정 장면부터 다시: 새 알림을 만든다. 도달한 알림은 다시 판정하지 않는다(도달한 알림은 같은 통화·방향 중복에 걸리지 않음).
- 로컬 알림만 다시: Reyes의 `notified_at`을 비우고 앱을 다시 연다(다시 연결하면 보충으로 다시 온다).

  ```bash
  dc exec -T mysql sh -c 'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' <<'SQL'
  UPDATE fx_alerts a JOIN users u ON u.id = a.user_id
  SET a.notified_at = NULL
  WHERE u.external_user_key = 'demo-e9-ph-reyes' AND a.status = 'TRIGGERED';
  SQL
  ```

#### 앱 없이 curl로 확인

```bash
TOKEN=$(curl -fsS -X POST https://<도메인>/api/v1/auth/entry -H 'Content-Type: application/json' \
  -d '{"entryToken":"demo-e9-ph-reyes"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
curl -N -H "Authorization: Bearer $TOKEN" https://<도메인>/api/v1/fx/alerts/stream
```

연결해 둔 채 다른 창에서 3번(다시 판정)을 하면 아래처럼 온다(11/18 로컬 출력). `-N`을 빼면 curl이 출력을 모아 두어 늦게 보인다.

```text
event:ready
data:{"pingIntervalSeconds":25}

id:181
event:fx-alert-triggered
data:{"alertId":181,"currency":"PHP","rateType":"BASE","comparator":"GTE","targetRate":20.484,"unitAmount":1,"triggeredRate":20.984,"triggeredAt":"2026-11-18T16:26:22+09:00","rateDate":"2026-11-17","source":"MARKET_REF"}

: ping
```

알렸음 처리(앱이 하는 일): `curl -fsS -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $TOKEN" https://<도메인>/api/v1/fx/alerts/181/notified` → `204`. 그 뒤 다시 연결하면 `event:ready`만 온다.

## Swagger UI 기본 인증

운영 Swagger UI는 `https://<도메인>/swagger-ui/index.html`이고, Caddy 기본 인증(계정 1개)으로 막는다. 문서에는 `/api/v1`만 나오며 내부 API(`/internal/v1`)와 내부 토큰 설명은 빠진다.

### 비밀번호 해시 만들기

서버에서 실행한다. 비밀번호를 프롬프트로 두 번 입력하므로 평문이 셸 기록에 남지 않는다.

```bash
docker run --rm -it caddy:2 caddy hash-password
```

출력된 `$2a$14$...` 전체가 해시다. 비밀번호 평문은 팀에 따로 전달하고 `.env`에는 해시만 넣는다.

### deploy/.env에 넣기

```bash
SWAGGER_BASIC_USER=hanstep
SWAGGER_BASIC_HASH='$2a$14$...'
```

- 해시는 **반드시 작은따옴표**로 감싼다. 따옴표가 없거나 큰따옴표면 compose가 `$2a` 같은 부분을 변수로 치환해 해시가 깨진다.
- 확인(값이 화면에 나오므로 공유하지 않는다): `docker compose -f deploy/compose.prod.yml config | grep SWAGGER_BASIC_HASH` 결과가 `$$2a$$14$$` 뒤로 해시 전체(53자)가 이어져야 한다(config 출력은 `$`를 `$$`로 표시). 따옴표를 빠뜨리면 `$$2a$$14/...`처럼 가운데가 잘린다.

### 이미 떠 있는 서버에 적용하는 순서

compose.prod.yml이 두 변수를 `${...:?}`(필수)로 읽는다. 변수가 없는 상태에서 새 코드를 pull하면 `up`뿐 아니라 `dc ps`, `dc logs` 같은 **모든 compose 명령이 오류로 멈춘다**. 그래서 순서를 지킨다.

1. **`deploy/.env`에 `SWAGGER_BASIC_USER`·`SWAGGER_BASIC_HASH`를 먼저 추가한다.**
2. **그다음 `git pull`.**
3. `docker compose -f deploy/compose.prod.yml config --quiet && echo OK`
4. `docker compose -f deploy/compose.prod.yml up -d --build` (Spring은 `prod` 프로필로 다시 빌드·기동되고, Caddy는 새 Caddyfile·환경 변수로 다시 만들어진다)

### 배포 뒤 확인

- [ ] 인증 없이 `curl -s -o /dev/null -w '%{http_code}\n' https://<도메인>/swagger-ui/index.html` → `401`
- [ ] 브라우저로 같은 주소를 열고 계정을 입력하면 Swagger UI가 보이고, 위쪽 Servers가 `https://<도메인>`이다(`http://`이면 Try it out이 막힌다)
- [ ] `curl -s -o /dev/null -w '%{http_code}\n' https://<도메인>/internal/v1/ai-context/documents/1/source` → `404`
- [ ] 문서 목록에 `/internal/...` 경로가 없다
- [ ] Try it out으로 `POST /api/v1/auth/entry`(entryToken `demo-e9-ph-reyes`) → 받은 `accessToken`을 Authorize에 넣기 → 다른 API(예: `GET /api/v1/onboarding`) 호출이 200

## 7. 비용 절약

- 밤에는 인스턴스를 중지하고 아침에 시작한다. 모든 서비스가 `restart: unless-stopped`이고 Docker가 부팅 시 시작되므로(`systemctl enable docker`), 인스턴스를 켜면 compose 서비스도 다시 뜬다.
- **11/19 ~ 11/22, 11/27(최종 발표)은 중지하지 않고 계속 켜 둔다.**
- 다시 켠 뒤 확인: `dc ps`, `curl https://<도메인>/api/v1/health`

## 8. 발표 후 정리

1. 인스턴스 종료(삭제)
2. 디스크(볼륨)와 스냅샷 삭제
3. 고정 IP 해제(연결되지 않은 고정 IP도 과금될 수 있음)

## 측정 기록

메모리 사용량. `mem_limit`은 서버 실측 후 정한다.

| 항목 | 로컬 실측 | 서버 실측 |
|---|---|---|
| 조건 | 11/16 09:43, Windows + Docker Desktop, BE 6117f44 · AI 771a4e1, demo-setup 후, BGE-M3 적재, Mongo 인덱스·문서 없음, 1회 | |
| MySQL | 451 MiB | |
| RabbitMQ | 216 MiB | |
| MongoDB | 641 MiB | |
| Spring | 사설 369 MB / 작업 집합 332 MB | |
| AI 서버 | 사설 3,473 MB / 작업 집합 2,047 MB | |
| AI Worker | 미측정 | 서버 실측 예정 |
| OCR | 미측정 | |
| Caddy | 미측정 | |
