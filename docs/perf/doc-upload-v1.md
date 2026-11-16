# 등록증 업로드(DOC) 기준선 v1

이슈 #73(외국인등록증 업로드·확인·확정 API와 발행 대기 재발행) 본 구현 전에 지금 코드와 업로드 경로 후보를 잰 기준선이다.
측정만 했고 main 코드는 바꾸지 않았다. 원본은 `build/perf/` 아래(커밋 안 함).

| 기준선 | 테스트 | 원본 |
|---|---|---|
| 1. U2 브로커 멈춤 | `src/test/java/com/hanstep/aijob/messaging/OcrJobBrokerDownPerfTest.java` | `build/perf/doc-u2-broker-down.md`, `build/perf/doc-u2-broker-down-raw.csv` |
| 2. 업로드 경로(R2) | `src/test/java/com/hanstep/document/DocUploadR2PerfTest.java` | `build/perf/doc-upload-r2.md`, `build/perf/doc-upload-r2-raw.csv` |

## 기준선 1 — U2: 브로커가 멈췄을 때 지금 코드(develop 6117f44)

### 조건

| 항목 | 값 |
|---|---|
| 측정 시각 | 2026-11-16 10:09 (+09:00), 1회 |
| 환경 | Windows 11, CPU 8 logical, Java 21.0.12.1, MySQL 8.4.13(로컬 Docker 공용 컨테이너) |
| 브로커 | 이 테스트 전용 Testcontainers `rabbitmq:4-management`. `rabbitmqctl stop_app`/`start_app`으로 멈췄다 켬(포트 그대로). 공용 `hanstep-rabbitmq`는 쓰지 않음 |
| 작업 등록 경로 | 호출한 쪽 트랜잭션(TransactionTemplate) 안에서 `AiJobEnqueueService.enqueue` → 커밋 후 `AiJobPublishListener`가 발행(기존 경로 그대로) |
| OCR 작업 | 등록증 업로드가 할 묶음을 흉내: 같은 트랜잭션에서 `documents` 1행(ARC, FRONT) + OCR 작업 1건 |
| 데이터 | `TestUsers` 사용자 1명(`test-perf-u2-{runId}`), 실제 커밋, 끝나고 사용자·작업 삭제(documents 는 CASCADE). 정리 후 남은 행 0건 확인 |
| 설정 | `spring.rabbitmq.connection-timeout=1s`(이 테스트만), confirm-timeout 5s(기본) |
| 명령 | `.\gradlew.bat perfTest --tests "com.hanstep.aijob.messaging.OcrJobBrokerDownPerfTest"` |

단계: A OCR·브로커 멈춤 10건 → C POLICY_INGEST·브로커 멈춤 10건 → 브로커 다시 켬, 30초 관찰 → B OCR·브로커 켬 10건 → D POLICY_INGEST·브로커 켬 10건.
C·D 는 지금 발행 경로가 있는 유일한 작업이라 비교용으로 넣었다(B 는 OCR 실패 원인 분리, D 는 측정 도구 확인).

### 결과

| 단계 | 조건 | 건수 | 호출 쪽 예외 | 롤백(documents·ai_jobs 사라짐) | 걸린 시간 중앙값 / 최대(ms) | 결과 상태 |
|---|---|---|---|---|---|---|
| A | OCR, 브로커 멈춤 | 10 | 0 | 0 (documents 10·ai_jobs 10 남음) | 29 / 345 | PENDING / PUBLISH_FAILED 10 |
| C | POLICY_INGEST, 브로커 멈춤 | 10 | 0 | 0 (ai_jobs 10 남음) | 28 / 146 | PENDING / PUBLISH_FAILED 10 |
| B | OCR, 브로커 켬 | 10 | 0 | 0 (documents 10·ai_jobs 10 남음) | 98 / 152 | PENDING / PUBLISH_FAILED 10 |
| D | POLICY_INGEST, 브로커 켬 | 10 | 0 | 0 | 43 / 109 | QUEUED / PUBLISHED 10 |

| 항목 | 값 |
|---|---|
| ai_jobs publish_status 별(A·C 뒤) | NOT_PUBLISHED 0, PUBLISHED 0, PUBLISH_FAILED 20(OCR 10, POLICY_INGEST 10) |
| 브로커를 다시 켠 뒤 30초 동안 자동 발행 | **0건**(1·5·10·…·30초 모두 그대로, policy.ingest 큐 메시지 0) |
| 실패 이유(로그) | OCR 20건: `IllegalArgumentException: unsupported AI job type: OCR`(“publish failed unexpectedly”) / POLICY_INGEST 10건: `SEND_FAILED: AmqpIOException` |

### 읽은 것

1. **지금 OCR 작업은 브로커 상태와 상관없이 발행되지 않는다.** `AiJobRoute`에 OCR 경로가 없어(`POLICY_INGEST`만) `AiJobRoute.forType(OCR)`이 예외를 낸다. 브로커를 켠 B 도 10건 모두 PUBLISH_FAILED. 본 구현에서 OCR 경로(큐·재시도·DLQ)를 추가해야 한다.
2. **발행 실패가 요청을 실패로 만들지 않는다.** 호출 쪽 예외 0, 롤백 0. 저장(documents·ai_jobs)은 커밋된 채 남고 작업은 `status=PENDING`, `publish_status=PUBLISH_FAILED`.
3. **다시 켜도 자동 재발행은 없다.** 30초 동안 0건. 합의안의 수동 재발행(U2)이 필요하다는 것을 확인했다.
4. 걸린 시간은 커밋 + 커밋 후 발행 시도(같은 스레드)까지다. `stop_app`은 포트를 닫아 연결이 바로 거절되므로 빨리 끝났다. 네트워크가 끊겨 응답이 없는 경우는 connection-timeout(운영 설정 3s)까지 기다릴 수 있다(이번에 재지 않음).

## 기준선 2 — 업로드 경로: multipart vs presigned (R2)

### 실행 명령(사용자)

로컬 Docker MySQL 이 떠 있어야 한다. R2 값은 Spring 이 `.env`를 읽는 방식(`spring.config.import`) 그대로 쓴다.

```powershell
.\gradlew.bat perfTest --tests "com.hanstep.document.DocUploadR2PerfTest"
```

- 쓰는 키: `R2_ENDPOINT_URL`, `R2_REGION`(없으면 auto), 등록증 버킷 전용 키 `R2_OCR_ACCESS_KEY_ID`·`R2_OCR_SECRET_ACCESS_KEY`, `R2_ARC_BUCKET`. 정책·상품 키(`R2_ACCESS_KEY_ID`)는 쓰지 않는다. 넷(REGION 제외) 중 하나라도 비면 비어 있는 변수 이름을 출력하고 건너뛴다.
- 키에 `R2_ARC_BUCKET` 읽기·쓰기·목록·삭제 권한이 없으면 403 으로 실패한다.
- 객체는 `R2_ARC_BUCKET`의 `perf/{runId}/` 아래에만 올리고, 끝나면(실패해도) `perf/` 아래를 모두 지운다.
- 업로드량: 1 MiB·5 MiB 를 합쳐 약 133건, 약 400 MiB.

### 조건

| 항목 | 값 |
|---|---|
| 측정 시각 | 2026-11-16 16:19 실행(리포트 16:21:03 +09:00), 1회 실행 |
| 측정 도구 커밋 | e65c8e7(기준 코드 develop 6117f44) |
| 환경 | 로컬 Windows 11, CPU 8 logical, Java 21.0.12.1, JVM 최대 힙 512 MiB, MySQL 8.4.13(로컬 Docker). R2 는 실제 Cloudflare R2(사용자 PC 네트워크) |
| 이미지 | 합성 JPEG 1000×630, "SAMPLE" 글자. JPEG COM 세그먼트로 정확히 1,048,576 / 5,242,880 바이트로 맞춤. JDK `ImageIO`로 디코딩되는 것 확인 |
| ① multipart | 클라이언트 → Spring(RANDOM_PORT) `POST /perf/doc-upload/multipart` → 형식(image/jpeg·png)·크기(≤10 MiB)·매직 바이트·sha256 검사 → R2 PutObject |
| ② presigned | `POST /perf/doc-upload/presign`(형식·크기 검사, PUT URL 5분) → 클라이언트가 R2 에 직접 PUT → `POST /perf/doc-upload/complete` → Spring 이 HEAD 로 크기 확인 |
| 반복 | 경로·크기마다 워밍업 2회 + 순차 20회(p50·p95, nearest-rank), 동시 10건 1회(힙 최대), 완료 알림 없는 presigned 5건(고아) |
| 힙 | `MemoryMXBean` 힙 사용량을 5ms 마다 표본. 시작 전 `System.gc()` 후 값과 최대값 |
| 인증 | `TestUsers` 사용자의 JWT(시제품 경로도 일반 API 와 같은 인증) |
| SDK | `software.amazon.awssdk:s3` 2.59.1(testImplementation), path-style, checksum WHEN_REQUIRED |

### 결과

전체 133건(워밍업 16 + 순차 80 + 동시 40 + 고아 5) 모두 성공, 실패 0. 시간은 클라이언트가 첫 요청을 보내고 마지막 응답을 받을 때까지(presigned 는 presign + PUT + complete 합계).

순차(경로·크기마다 20회)

| 경로 | 크기 | 성공/실패 | p50(ms) | p95(ms) | 최대(ms) | 클라이언트 왕복 | Spring→R2 호출 |
|---|---|---|---|---|---|---|---|
| multipart | 1 MiB | 20 / 0 | 387 | 885 | 915 | 1 | 1(PutObject) |
| presigned | 1 MiB | 20 / 0 | 452 | 921 | 986 | 3 | 1(HeadObject) |
| multipart | 5 MiB | 20 / 0 | 647 | 1,208 | 1,238 | 1 | 1(PutObject) |
| presigned | 5 MiB | 20 / 0 | 574 | 1,059 | 2,042 | 3 | 1(HeadObject) |

동시 10건(1회)

| 경로 | 크기 | 성공/실패 | 성공 건 p50(ms) | 성공 건 최대(ms) | 전체 시간(ms) | 시작 전 힙 | 최대 힙 | 힙 증가 |
|---|---|---|---|---|---|---|---|---|
| multipart | 1 MiB | 10 / 0 | 670 | 781 | 806 | 73.6 MiB | 134.1 MiB | 60.5 MiB |
| presigned | 1 MiB | 10 / 0 | 550 | 1,145 | 1,145 | 86.8 MiB | 115.8 MiB | 29.0 MiB |
| multipart | 5 MiB | 10 / 0 | 1,472 | 1,891 | 2,035 | 96.4 MiB | 277.3 MiB | 180.9 MiB |
| presigned | 5 MiB | 10 / 0 | 1,020 | 1,432 | 1,449 | 96.5 MiB | 215.5 MiB | 119.0 MiB |

완료 알림 누락·정리

| 항목 | 값 |
|---|---|
| complete 없이 PUT 만 한 presigned(1 MiB) | 5건(왕복 2, Spring→R2 호출 0) |
| R2 에 남은 고아 객체 | **5건(5건 모두)** |
| 시작 전 perf/ 잔여 객체 | 0건 |
| 끝 정리 | perf/ 133건 중 133건 삭제, 남은 0건 |

### 읽은 것

1. **1회 실행·같은 네트워크 기준으로 지연 차이는 크기·동시성에 따라 갈린다.** 순차 1 MiB 는 multipart 가 p50 65 ms 빨랐고, 순차 5 MiB 는 presigned 가 p50 73 ms 빨랐다. p95 는 두 경로가 비슷했다(차이 36~149 ms). 표본 20건·1회라 이 정도 차이는 네트워크 흔들림 안일 수 있다.
2. **동시 5 MiB 에서 차이가 가장 컸다.** 성공 건 p50 multipart 1,472 ms / presigned 1,020 ms, 힙 증가 180.9 / 119.0 MiB. multipart 는 Spring 이 파일 전체를 받아 메모리에 올린 뒤 다시 R2 로 보내므로 Spring 쪽 대역폭·메모리를 두 번 쓴다.
3. **presigned 는 완료 알림이 빠지면 객체가 그대로 남는다(5/5).** presigned 를 고르면 고아 객체 정리(대기 중 document 만료 + 버킷 수명 주기 규칙 등)가 필요하다.
4. presigned 는 클라이언트 왕복이 3번(multipart 1번)이고, Spring 은 HEAD 로 크기만 확인한다(sha256·매직 바이트는 검사 못 함).

## 공개할 것

- **기준선 1 측정 도구 변경 없음.** 기준선 1 은 위 테스트를 처음 작성해 한 번 돌린 결과이고, 측정 뒤 테스트를 고치지 않았다.
- **측정 기준 커밋:** 6117f44. develop e6e3d9a 의 #74 retry 변경 이전 코드에서 쟀다.
- **OCR 20건 실패 원인:** 브로커 영향이 아니라 `AiJobRoute`에 OCR 경로가 없어서였다. 브로커 영향은 POLICY_INGEST(C)로 확인했다.
- **용어:** 요청에 적힌 "publish_status PENDING"은 스키마에 없다. 스키마 값은 `NOT_PUBLISHED / PUBLISHED / PUBLISH_FAILED`이고, PENDING 은 `status` 값이다. 표에 둘 다 적었다.
- **OCR 경로 없음:** 기준선 1 의 OCR 결과(A·B)는 브로커 멈춤 효과가 아니라 경로 부재 때문이다. 브로커 멈춤 효과는 POLICY_INGEST(C)로 봐야 한다.
- 기준선 1 의 브로커 멈춤은 `stop_app`(포트 닫힘, 즉시 거절)이다. 응답 없는 네트워크 단절과는 다르다.
- **기준선 2 측정 도구 수정 경위:** 2026-11-16 15:35 첫 실행(커밋 f92bd0f)이 presigned 동시 10건 단계에서 클라이언트 → R2 직접 PUT 한 건이 `SSLException: (bad_record_mac) Received fatal alert: bad_record_mac`(`HTTP/1.1 header parser received no bytes`)로 끝나 테스트 전체가 예외로 멈췄다. 결과를 마지막에 한꺼번에 쓰는 구조라 남은 결과가 없었다(1 MiB·5 MiB 중 어느 크기였는지도 모름). 결과를 보기 전에 도구만 고쳤다: ① 업로드 한 건이 실패해도 예외로 끝내지 않고 실패 건수·종류를 기록(재시도 없음, 시간 통계는 성공 건만) ② raw.csv 를 단계마다 덧붙여 씀 ③ 시작 전에 `perf/` 잔여 객체(15:35 실패분)를 세고 지움, 끝나면 남은 개수 0 확인. 측정 조건(이미지·반복 횟수·경로)은 바꾸지 않았다.
- **기준선 2 실행 이력:** 15:35 1회 실패(위 `bad_record_mac`, 결과 없음) → 16:19 e65c8e7 로 재실행 1회, 133건 실패 0(재시도 없이). 표는 16:19 실행 값이다. 16:19 시작 때 perf/ 잔여 객체는 0건이었다(15:35 실행의 tearDown 정리가 돈 것으로 보이나 그 로그는 남아 있지 않다). `bad_record_mac`은 이번에 재현되지 않았고 원인은 확인하지 못했다.
- 기준선 2 의 raw.csv `r2_calls` 는 Spring 전체 카운터의 앞뒤 차이라, 동시 단계에서는 다른 요청의 호출이 섞인다(1~10). 표의 Spring→R2 호출 수는 순차 값이다.
- 기준선 2 는 1회 실행이다. 순차 p50 차이(65~73 ms)는 반복 실행으로 확인하지 않았다.
- 기준선 2 는 클라이언트와 Spring 이 같은 JVM 이라 힙은 JVM 전체 값이다. 업로드 본문은 크기마다 한 번 만든 배열을 같이 쓴다(동시 10건이 클라이언트 쪽 배열을 10개 만들지 않음).
- 기준선 2 의 multipart 시제품은 파일 전체를 메모리로 읽는다(`MultipartFile.getBytes()`). 스트리밍으로 구현하면 힙 값이 달라진다.
- 기준선 2 의 presigned 는 Spring 이 sha256 을 검사하지 못한다(HEAD 로 크기만 확인). multipart 와 검사 범위가 다르다.
- 기준선 2 의 multipart 크기 제한(`spring.servlet.multipart.max-file-size=10MB` 등)은 이 테스트에만 넣었다. 운영 설정은 바꾸지 않았다(기본 1MB).
- 기준선 2 의 왕복 시간은 사용자 PC 네트워크에서 R2 까지를 포함한다. 서버(배포 환경)에서 재면 달라진다.
