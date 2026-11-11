# hanstep-backend
신한커리어넥스트 2회차 프로젝트 | 국내 거주 외국인의 금융생활 및 체류자격 성장을 지원하는 AI 플랫폼 '신한걸음'의 백엔드 레포지토리입니다.



## 로컬 실행

### 준비
- JDK 21, Docker Desktop
- `.env.example`을 복사해 `.env`를 만들고 값을 채운다.

### 실행
```powershell
docker compose up -d      # MySQL(3307), RabbitMQ, MongoDB 실행
./gradlew bootRun         # 또는 IntelliJ에서 HanstepBackendApplication 실행
```

### 공통 규칙
- 외부 API: `/api/v1`, 내부 API(AI 서버용): `/internal/v1`
- 오류 응답: `{ code, message, details, requestId }`
- 스키마 기준: `db/init/*.sql`

### 인증 (앱인앱 진입)
- 자체 로그인 없음. `POST /api/v1/auth/entry` 에 SOL 진입 토큰을 보내 JWT를 받는다. 데모 토큰: `demo-e9-nguyen` (`db/init/03_hanstep_demo_seed.sql`)
- 이후 요청은 `Authorization: Bearer {accessToken}`. 만료(기본 1시간) 시 401 → 다시 `/auth/entry`
- `.env`에 `JWT_SECRET`이 없으면 서버가 시작되지 않는다. 만드는 방법은 `.env.example` 참고
- Swagger: http://localhost:8080/swagger-ui.html → Authorize 버튼에 accessToken 입력

### AI 서버 연동 (내부 API)
- AI → Spring(`/internal/v1/**`): AI가 `X-Internal-Token: {SPRING_INTERNAL_API_KEY}`를 보낸다
- Spring → AI: `aiRestClient` 빈이 `AI_INTERNAL_API_URL`(기본 `http://localhost:8000`)로 `X-Internal-Token: {AI_INTERNAL_API_TOKEN}`, `X-Request-Id`를 붙여 호출한다
- 두 키 모두 AI 서버 `.env`와 같은 값, 32자 이상. 방향마다 다른 값을 쓴다. `AI_INTERNAL_API_TOKEN`이 없어도 서버는 뜨지만 시작 로그에 경고가 남고 AI 호출은 401
