# Routinely Backend — CLAUDE.md

## 1. 기술 스택

| 항목 | 선택 | 비고 |
|---|---|---|
| Language | Java 21 | Virtual Threads 활용 가능 |
| Framework | Spring Boot 4.0.5 | |
| Build Tool | Gradle (Groovy DSL) | 멀티모듈 |
| Group ID | `com.routinely` | |
| ORM | Spring Data JPA + QueryDSL | 복잡한 조회는 QueryDSL |
| Security | Spring Security + JWT | Gateway에서 중앙 검증 (ADR-0006) |
| Service Discovery | Eureka (registry-service) | Config Server 미사용 (ADR-0020) |
| gRPC | grpc-spring-boot-starter | libs/proto 중앙 관리 |
| Messaging | Apache Kafka | Outbox 패턴으로 발행 (ADR-0012) |
| Job Queue | PGMQ | PostgreSQL 확장, 알림 예약 전용 (ADR-0009) |
| DB | PostgreSQL × 5 | 서비스별 독립 DB |
| Cache | Redis | Rate Limiting, ZSET 랭킹, 캐시 |
| File Storage | AWS S3 | `FileStorage` 인터페이스로 추상화 |
| Resilience | Resilience4j | timeout / retry / circuit breaker |
| API 문서 | Swagger (springdoc openapi) | local/dev 프로파일에서만 활성화 |
| 분산 추적 | Micrometer Tracing + Zipkin | traceId/spanId 자동 주입 |
| 로그 수집 | Grafana Alloy → Loki | JSON 구조 로그 stdout 출력 |
| 메트릭 | Prometheus + Grafana | |
| CI/CD | GitHub Actions | PR: build/test / merge: docker build/push |

---

## 2. 멀티모듈 구조

```
routinely-backend/
├── libs/
│   ├── common-core/          # 공통 유틸, 에러, ApiResponse, BaseEntity
│   ├── common-web/           # Web 필터, MDC, 공통 ExceptionHandler
│   ├── common-observability/ # 로깅/트레이싱 Bean 설정
│   └── proto/                # gRPC Proto 정의 + 생성 스텁
├── services/
│   ├── registry-service/     # Eureka Server :8761
│   ├── gateway/              # Spring Cloud Gateway :8080
│   ├── user-service/         # :8081 / gRPC :9081
│   ├── routine-service/      # :8082 / gRPC :9082
│   ├── challenge-service/    # :8083 / gRPC :9083
│   ├── chat-service/         # :8084 / gRPC :9084
│   └── notification-service/ # :8085 / gRPC :9085
├── infra/
│   └── docker-compose.yml
├── scripts/
│   ├── local-up.sh / local-down.sh / db-migrate.sh
└── docs/
    ├── decisions/            # ADR-0001 ~ ADR-0025
    ├── conventions/          # 구현 패턴 참고 문서
    └── ...
```

### libs 의존성

| 서비스 | 의존하는 libs |
|---|---|
| registry-service | 없음 |
| gateway | common-core, common-web, common-observability |
| 나머지 서비스 5개 | common-core, common-web, common-observability, proto |

> Gateway는 Spring WebFlux 기반 — common-web 적용 가능 여부 구현 시 검토

---

## 3. 서비스별 DB 및 포트

| 서비스 | HTTP 포트 | gRPC 포트 | PostgreSQL DB |
|---|:---:|:---:|---|
| registry-service | 8761 | — | — |
| user-service | 8081 | 9081 | routinely_user |
| routine-service | 8082 | 9082 | routinely_routine |
| challenge-service | 8083 | 9083 | routinely_challenge |
| chat-service | 8084 | 9084 | routinely_chat |
| notification-service | 8085 | 9085 | routinely_notification |

---

## 4. 서비스 내부 패키지 구조

```
com.routinely.{service}/
├── domain/          # Entity, VO, Domain Service, Repository Interface
├── application/     # Use Case / Service (비즈니스 로직 조합)
├── infrastructure/
│   ├── persistence/ # JPA Repository 구현, QueryDSL
│   ├── kafka/       # Producer(Outbox Worker), Consumer(@RetryableTopic)
│   ├── grpc/        # gRPC Server / Client Stub
│   └── pgmq/        # PGMQ Worker (notification-service 전용)
├── presentation/
│   ├── rest/        # REST Controller, Request/Response DTO
│   └── grpc/        # gRPC Service 구현
└── {Service}Application.java
```

---

## 5. 코딩 컨벤션

### 공통 응답 포맷

모든 REST 응답은 `ApiResponse<T>` (common-core)로 통일.

```java
ApiResponse.ok("챌린지 생성에 성공했습니다.", data)   // { success: true, message: "...", data: {...} }
ApiResponse.ok("탈퇴가 완료되었습니다.")              // { success: true, message: "..." }
ApiResponse.fail("CHALLENGE_NOT_FOUND", "...")        // { success: false, message: "...", errorCode: "..." }
```

### HTTP 상태코드 규칙

| 코드 | 사용 시점 |
|:---:|---|
| 200 | 조회 / 수정 / 삭제 성공 |
| 201 | 생성 성공 |
| 400 | 유효성 검사 실패 |
| 401 | 인증 실패 (토큰 없음 / 만료) |
| 403 | 권한 없음 |
| 404 | 리소스 없음 |
| 409 | 중복 / 충돌 |

### 네이밍 컨벤션

- **패키지**: `com.routinely.{service}.{layer}` (소문자)
- **클래스**: PascalCase — `RoutineExecutionService`, `ChallengeController`
- **메서드/변수**: camelCase — `findActiveMembers()`, `routineTemplateId`
- **상수**: UPPER_SNAKE_CASE — `MAX_RETRY_COUNT`
- **DB 컬럼**: snake_case — `created_at`, `user_id`
- **Kafka 토픽**: `{도메인}.{집합체}.{과거형동사}` — `routine.execution.completed`
- **에러 코드**: UPPER_SNAKE_CASE + 도메인 접두사 — `CHALLENGE_NOT_FOUND`

### 예외 처리 전략

- `ErrorCode` enum + `BusinessException` 단일 클래스로 통일 — 도메인별 예외 클래스 다수 생성 금지
- 유효성 검사는 Controller 레이어에서만 수행 — `ValidationAdvice` AOP가 자동 처리
- 전역 예외 처리는 `GlobalExceptionHandler` (common-web)에서 담당

> 구현 예시 → `docs/conventions/exception-handling.md`

### Entity 작성 규칙

- `@Setter` 사용 금지 — 상태 변경은 의미 있는 메서드(`end()`, `activate()`)로 표현
- `@Builder` + `@NoArgsConstructor(PROTECTED)` 조합
- `extends BaseEntity` — `createdAt`, `updatedAt` 자동 관리

> 구현 예시 → `docs/conventions/entity-repository.md`

### Service 계층 규칙

- Service 인터페이스 + `ServiceImpl` 항상 분리
- 클래스 레벨 `@Transactional(readOnly = true)` 기본 적용
- 쓰기 메서드에 `@Transactional` 오버라이드 필수 (빠뜨리면 readOnly 트랜잭션으로 INSERT 시도)
- `findXxxByIdOrThrow()` private 헬퍼로 `orElseThrow` 중복 제거

> 구현 예시 → `docs/conventions/service-dto.md`

### DTO 작성 규칙

- 도메인별 중첩 static class로 묶어 관리 — `ChallengeDto.CreateRequest`, `ChallengeDto.CreateResponse`
- Request DTO: `@Getter` + `@NoArgsConstructor(PROTECTED)` (Jackson 역직렬화용 기본 생성자 필수)
- Response DTO: `@Getter` + `@Builder`

> 구현 예시 → `docs/conventions/service-dto.md`

### Request DTO 유효성 검사

유효성 검사는 **Controller 레이어에서만** — Service에서 중복 검사 금지.

| 어노테이션 | 대상 타입 | 용도 |
|---|---|---|
| `@NotNull` | 모든 타입 | null 거부 |
| `@NotBlank` | String | null / 빈 문자열 / 공백 거부 |
| `@NotEmpty` | String, Collection | null / 빈 값 거부 (공백 허용) |
| `@Size(min, max)` | String, Collection | 길이 범위 |
| `@Min` / `@Max` | 숫자 | 범위 제한 |
| `@Email` | String | 이메일 형식 |
| `@Future` / `@FutureOrPresent` | 날짜 | 미래 / 오늘 이후 |

> String 필드는 `@NotBlank`, 숫자·Boolean·날짜는 `@NotNull` 사용  
> `message` 속성 항상 한국어로 직접 지정 — 기본 메시지(`must not be blank`) 사용 금지

> 구현 예시 → `docs/conventions/controller.md`

### Controller 작성 규칙

- `userId`: 항상 `@RequestHeader("X-User-Id")`로 수신 — JWT 재파싱 금지
- `BindingResult` 파라미터 선언 금지 — 없어야 Spring이 `MethodArgumentNotValidException`을 자동으로 던짐
- 반환 타입: `ResponseEntity<ApiResponse<T>>`

> 구현 예시 → `docs/conventions/controller.md`

### 상수 관리

Kafka 토픽명, 헤더 키 등은 common-core의 interface(`KafkaTopics`, `HeaderConstants`)로 관리.

> 구현 예시 → `docs/conventions/service-dto.md`

### Lombok 레이어별 조합

| 레이어 | 조합 |
|---|---|
| Entity | `@Getter` + `@NoArgsConstructor(PROTECTED)` + `@Builder` |
| Request DTO | `@Getter` + `@NoArgsConstructor(PROTECTED)` |
| Response DTO | `@Getter` + `@Builder` |
| Service / Component | `@RequiredArgsConstructor` + `@Slf4j` |
| Controller | `@RequiredArgsConstructor` |
| Config | `@RequiredArgsConstructor` |

### Repository 규칙

- 단순 조회 → JPA 메서드명
- JOIN / 집계 → `@Query`
- 동적 조건 / 페이지네이션 → QueryDSL

> 구현 예시 → `docs/conventions/entity-repository.md`

### Outbox 패턴

- `kafkaTemplate.send()` 직접 호출 금지 — 반드시 Outbox 테이블 경유
- 도메인 저장 + Outbox INSERT는 같은 `@Transactional` 내에서 수행

> 구현 예시 → `docs/conventions/outbox-pattern.md`

### 로그 작성 규칙

| 레벨 | 사용 시점 |
|---|---|
| `ERROR` | 예상치 못한 예외, 외부 시스템 장애 (gRPC 실패, Outbox 발행 실패) |
| `WARN` | 비즈니스 예외, 재시도 (BusinessException, 유효성 검사 실패) |
| `INFO` | 주요 비즈니스 이벤트 (챌린지 생성, 루틴 완료) |
| `DEBUG` | 개발 디버깅용 (local/dev 프로파일에서만) |

- 파라미터는 로그 메시지에 포함 — 문자열 연결 금지 (`log.info("...", value)`)
- 민감 정보(비밀번호, 토큰) 로그 출력 금지
- traceId / userId는 MDC 필터가 자동 주입

### 테스트 작성 규칙

| 테스트 종류 | 어노테이션 | 대상 |
|---|---|---|
| 단위 테스트 | `@ExtendWith(MockitoExtension.class)` | Service, 도메인 로직 |
| 슬라이스 — Repository | `@DataJpaTest` | JPA 쿼리 |
| 슬라이스 — Controller | `@WebMvcTest` | HTTP 형식, 유효성 검사 |

- 테스트 메서드명: `{동작}_{시나리오}` — `join_success`, `join_alreadyJoined_throwsException`
- `@DisplayName` 한글 필수

> 구현 예시 → `docs/conventions/testing.md`
