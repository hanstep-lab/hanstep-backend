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