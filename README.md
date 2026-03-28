# worker

`worker`는 결제 플랫폼의 비동기 후처리를 담당하는 백그라운드 애플리케이션입니다.  
주요 책임은 **Webhook 전송 파이프라인 운영**과 **정산(Settlement) 집계/대사 처리**입니다.

## 1. 기술 스택

- Kotlin, Spring Boot 3.5.x
- Java 21
- Spring Data JPA, Web, Actuator, Scheduling
- Spring Cloud AWS SQS + AWS SDK v2
- MySQL, Redis
- QueryDSL
- OpenAPI(springdoc)
- `com.gop.logging:gop-logging-spring-starter`

## 2. 아키텍처 요약

`webhook`, `settlement`, `global` 3개 영역으로 구성됩니다.

- `webhook`
  - SQS 메시지 수신
  - 전송 대상 endpoint 조회 및 호출
  - 상태 전이(`SUCCESS`/`FAILED`/`DEAD`) 및 재시도 백오프
  - delivery/endpoint 이상 징후 Reconciliation
- `settlement`
  - 정산 원장 생성
  - 외부 정산 데이터와 내부 원장 대사
  - 미일치 유형 분류 및 기록
- `global`
  - datasource/config, 예외 처리, 공통 로깅 컨텍스트

## 3. 핵심 동작

## 3.1 Webhook 전송 파이프라인

1. `WebhookDispatchSqsConsumer`가 SQS polling
2. 메시지 핸들러가 delivery 저장/갱신
3. `WebhookDeliveryWorker`가 due delivery를 batch claim
4. `SendWebhookService`가 endpoint 단위 동시성 제어 후 전송
5. HTTP 결과를 `SUCCESS`/`RETRY`/`DEAD`로 분류
6. 재시도 시 백오프와 최대 시도 횟수 정책 적용

핵심 포인트
- endpoint 단위 동시성 제한으로 과도한 동시 호출 방지
- secret 복호화 실패 시 정책 기반 plaintext fallback 제어
- 전송 결과 메트릭과 구조화 로그를 함께 기록

## 3.2 Settlement 파이프라인

1. `SettlementDispatchSqsConsumer`가 정산 이벤트 수신
2. 원장/원시 데이터 저장
3. 스케줄러가 내부/외부 대사 수행
4. `SettlementReconciliationEngine`이 mismatch 유형 분류

분류 예시
- `MISSING_INTERNAL`, `MISSING_EXTERNAL`
- `AMOUNT_MISMATCH`, `FEE_MISMATCH`
- `DUPLICATED_INTERNAL`, `DUPLICATED_EXTERNAL`

## 3.3 Reconciliation(복구/감사)

- `WebhookReconciliationService`가 아래를 탐지
  - 장기 실패 delivery(STALE)
  - endpoint 열화(DEGRADED)
  - outbox 발행 대비 delivery 누락(MISSING)
- 탐지/해결 카운트를 메트릭으로 노출하여 운영 가시성 확보

## 4. 스케줄 작업

- `WebhookDeliveryWorker` (`webhook.worker.interval-ms`)
- `DeliveryLeaseSweeper` (`webhook.lease.sweep-interval-ms`)
- `WebhookReconciliationJob` (매일 04:00)
- `DailySettlementJob` (매일 02:00)
- `InternalReconciliationJob` (매일 03:00)
- `ExternalReconciliationJob` (매일 03:30)
- `SettlementRetryScheduler` (고정 주기)

## 5. 실행 방법

## 5.1 사전 준비
- JDK 21
- MySQL, Redis
- SQS(또는 로컬 ElasticMQ)
- GitHub Packages 접근 정보

## 5.2 필수 환경변수(핵심)

빌드 시
- `GOP_LOGGING_VERSION`
- `GITHUB_PACKAGES_URL`
- `GITHUB_PACKAGES_USER`
- `GITHUB_PACKAGES_TOKEN`

실행 시
- `LOG_SERVICE_NAME=worker`
- `DB_URL`, `DB_USER`, `DB_PASSWORD`
- `REDIS_HOST`, `REDIS_PORT`
- `AWS_REGION`
- `WEBHOOK_SQS_ENABLED`, `WEBHOOK_SQS_QUEUE_URL`
- `SETTLEMENT_SQS_ENABLED`, `SETTLEMENT_SQS_QUEUE_URL`
- `WORKER_BATCH_SIZE`, `WORKER_MAX_ATTEMPTS`

## 5.3 로컬 실행

```bash
./gradlew clean bootRun
```

## 5.4 테스트

```bash
./gradlew test
```

## 6. 로컬 SQS(ElasticMQ)

`docker-compose.yml`로 로컬 SQS를 띄울 수 있습니다.

```bash
docker compose up -d
```

- endpoint: `http://localhost:9324`
- config: `infra/docker/elasticmq/custom-sqs.conf`

## 7. 도커

```bash
./gradlew clean bootJar

docker build -t pg-worker:local .
docker run --rm -p 8080:8080 --env-file .env pg-worker:local
```

## 8. CI/CD

- `.github/workflows/reusable-build-and-push.yml`
  - `bootJar` 빌드 후 Docker image push
- `.github/workflows/deploy.yml`
  - self-hosted runner에서 컨테이너 롤링 교체
  - `/actuator/health` 확인 후 배포 완료

## 9. 면접 관점에서 강조할 점

- 메시지 소비 -> 상태 전이 -> 재시도/DEAD 처리까지 일관된 파이프라인 설계
- 비동기 워커에서 endpoint 단위 동시성 제어를 넣은 운영적 설계
- 대사 엔진에서 mismatch 분류 기준을 명시적으로 모델링한 점
- 스케줄 기반 복구 작업(Webhook/Settlement)을 제품 기능으로 내재화한 점
