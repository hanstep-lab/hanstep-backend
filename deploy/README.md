# 운영 배포 (서버 1대 + Docker Compose)

서버 1대(Ubuntu, x86, 16GB)에 MySQL·RabbitMQ·MongoDB·Spring·AI 서버·Caddy를 `deploy/compose.prod.yml` 하나로 띄운다.

- 외부에 여는 포트는 Caddy의 80·443뿐이다. 나머지 서비스는 compose 내부 네트워크에서 서비스 이름(`mysql`, `rabbitmq`, `mongodb`, `spring`, `ai-server`)으로 통신한다.
- Caddy는 `/api/v1/*`만 Spring으로 전달한다. `/internal/*`, `/swagger-ui*`, `/v3/api-docs*`는 외부에서 404로 막는다.
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
git clone <OCR 레포 주소>      # ocr-worker 는 이미지 미정이라 compose 에 주석으로만 있음
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
- 시연하려면 `E74_INCLUDE_DRAFT_RULES=true`(2026 규칙이 아직 DRAFT).
- MySQL·RabbitMQ·MongoDB 계정은 볼륨이 비어 있는 최초 기동 때만 적용된다.

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
- AI 서버는 `LOAD_MODEL_ON_STARTUP=true`라 처음에 BGE-M3(~2.3GB)를 받아 `hf-cache` 볼륨에 저장한다. 처음에는 몇 분 걸린다.
- Caddy는 Spring이 healthy가 된 뒤 뜨고, `DOMAIN`의 인증서를 자동 발급한다. 확인: `curl https://<도메인>/api/v1/health`

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

## 6. 데모 데이터

내 PC(Windows)의 `hanstep-backend`에서 실행한다.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\demo-setup.ps1 -BaseUrl https://<도메인>
```

마지막 줄의 여유자금이 **675,317 원**인지 확인한다.

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
| OCR | 미측정 | |
| Caddy | 미측정 | |
