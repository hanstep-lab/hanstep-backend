# 운영 배포 (서버 1대 + Docker Compose)

서버 1대(Ubuntu, x86, 16GB)에 MySQL·RabbitMQ·MongoDB·Spring·AI 서버·AI Worker·Caddy를 `deploy/compose.prod.yml` 하나로 띄운다.

- 외부에 여는 포트는 Caddy의 80·443뿐이다. 나머지 서비스는 compose 내부 네트워크에서 서비스 이름(`mysql`, `rabbitmq`, `mongodb`, `spring`, `ai-server`)으로 통신한다.
- `ai-worker`는 `ai-server`와 같은 이미지(`hanstep-ai:prod`)로 `python -m app.worker`를 실행한다. HTTP 포트 없이 RabbitMQ `policy.ingest` 큐를 소비해 정책 문서를 적재하고 Spring 내부 API로 상태를 보고한다. 1개만 띄운다.
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

## 2. 레포 3개를 나란히 clone

compose가 `../../hanstep-AI/ai`(AI 서버 빌드)와 `../../hanstep-AI/data/rag_documents`(RAG 원문)를 상대 경로로 쓰므로 같은 폴더에 나란히 둔다.

```bash
mkdir -p ~/hanstep && cd ~/hanstep
git clone https://github.com/hanstep-lab/hanstep-backend.git
git clone https://github.com/hanstep-lab/hanstep-AI.git
git clone <OCR 레포 주소>      # OCR 소비자는 hanstep-AI의 ocr-worker(ocr/worker)다(Spring 쪽 R2_OCR_* 는 #73에서 추가)
```

```
~/hanstep/
├── hanstep-backend/   ← 여기서 docker compose 실행
├── hanstep-AI/
└── <OCR 레포>/
```

## 3. deploy/.env 작성

```bash
cd ~/hanstep/hanstep-backend
cp deploy/.env.example deploy/.env
chmod 600 deploy/.env
vi deploy/.env
```

- 비밀번호는 `openssl rand -hex 24`처럼 영문·숫자로 만든다(MongoDB·RabbitMQ 비밀번호가 URI에 그대로 들어감).
- `SPRING_INTERNAL_API_KEY`는 32자 이상이다. compose가 같은 값을 Spring과 AI 서버 양쪽에 넣는다.
- `AI_INTERNAL_API_TOKEN`은 32자 이상이며 `SPRING_INTERNAL_API_KEY`와 다른 값으로 한다.
- `CHAT_LLM_MODE`를 비우면 `anthropic`(실제 호출, 유료)이라 `ANTHROPIC_API_KEY`가 필요하다. AI 코드 기본값은 `fake`(가짜 답변)다.
- `E74_INCLUDE_DRAFT_RULES`는 비워 둔다(false). 2026 규칙(`E74-2026-v1`)은 승인(APPROVED) 상태라 그대로 계산된다(#95).
- `R2_OCR_ACCESS_KEY_ID`·`R2_OCR_SECRET_ACCESS_KEY`는 외국인등록증 버킷(`R2_ARC_BUCKET`) 전용 키다. Spring이 등록증 앞면 업로드와 확정 시 원본 삭제에 쓴다(#73). 정책·상품 키(`R2_ACCESS_KEY_ID`)와 다른 키이고, 이 버킷의 읽기·쓰기·삭제 권한만 준다. 비어 있으면 업로드가 500으로 실패한다.
- Spring이 처음 RabbitMQ에 연결할 때 `ocr.request`·`ocr.request.retry`·`ocr.request.dlq` 큐를 선언한다(#73). OCR 소비자(hanstep-AI `ocr-worker`)는 아직 이 compose에 없어서, 붙기 전에 올린 등록증의 OCR 작업은 `ocr.request`에 쌓인다.
- 발행 대기 AI 작업(큐에 못 들어간 OCR·정책 적재 작업)은 Spring이 1분마다 다시 보낸다. 끄려면 `AI_JOB_REPUBLISH_ENABLED=false`를 spring 서비스 환경 변수에 넣는다.
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

- 최초 기동 때 MySQL이 `db/init`(스키마 → 기준 시드 → 데모 시드)를 실행한다.
- **이미 떠 있는 DB**(볼륨 있음)에는 `db/init` 변경이 들어가지 않는다. 그런 변경은 `deploy/migrations/`에 날짜별 SQL로 두고, `git pull` 뒤 `up` 전에 순서대로 실행한다. 빠뜨리면 Spring이 스키마 검증(`ddl-auto: validate`)에서 기동에 실패할 수 있다. 여러 번 실행해도 결과가 같게 만든다.
  - `2026-11-17-bill-payments-e74-approved.sql`: 청구 납부 테이블·Reyes 납부 내역(#87), E-7-4 2026 규칙 승인(#95). 실행 명령과 확인 쿼리는 파일 맨 위·맨 아래에 있다.
- AI 서버는 `LOAD_MODEL_ON_STARTUP=true`라 처음에 BGE-M3(~2.3GB)를 받아 `hf-cache` 볼륨에 저장한다. 처음에는 몇 분 걸린다.
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
- EC2가 꺼진 시간에 머지하면 Actions가 실패한다. 서버를 켠 뒤 해당 실행에서 Re-run을 누른다.
- 마이그레이션 파일은 여러 번 실행해도 결과가 같게 작성한다.
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

## 6. 데모 데이터

내 PC(Windows)의 `hanstep-backend`에서 실행한다.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\demo-setup.ps1 -BaseUrl https://<도메인>
```

마지막 줄의 여유자금이 **675,317 원**인지 확인한다.

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
