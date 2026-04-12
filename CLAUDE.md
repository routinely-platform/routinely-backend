# Routinely Backend — CLAUDE.md

---

## 1. 백엔드 기술 스택

| 항목 | 선택 | 비고 |
|---|---|---|
| Language | Java 21 | Virtual Threads 활용 가능 |
| Framework | Spring Boot 4.0.5 | |
| Build Tool | Gradle (Groovy DSL) | 멀티모듈 |
| Group ID | `com.routinely` | |
| ORM | Spring Data JPA + QueryDSL | 복잡한 조회는 QueryDSL |
| Security | Spring Security + JWT | Gateway에서 중앙 검증 (ADR-0006) |
| Service Discovery | Eureka (registry-service) | Spring Cloud Gateway + Eureka 연동, Config Server 미사용 (ADR-0020, ADR-0025) |
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
├── libs/                             # 공유 라이브러리
│   ├── common-core/                  # 공통 유틸, 에러, ApiResponse, BaseEntity
│   ├── common-web/                   # Web 필터, MDC, 공통 ExceptionHandler
│   ├── common-observability/         # 로깅/트레이싱 Bean 설정
│   └── proto/                        # gRPC Proto 정의 + 생성 스텁
│
├── services/
│   ├── registry-service/             # Eureka Server :8761
│   ├── gateway/                      # Spring Cloud Gateway :8080/:—
│   │   └── filter/                   # JWT 검증, MDC, Rate Limit 필터
│   ├── user-service/                 # :8081 / gRPC :9081
│   ├── routine-service/              # :8082 / gRPC :9082
│   ├── challenge-service/            # :8083 / gRPC :9083
│   ├── chat-service/                 # :8084 / gRPC :9084
│   └── notification-service/         # :8085 / gRPC :9085
│
├── infra/
│   ├── docker-compose.yml            # PostgreSQL, Redis, Kafka
│   └── k8s/                          # (확장) k8s manifests
│
├── scripts/
│   ├── local-up.sh
│   ├── local-down.sh
│   └── db-migrate.sh
│
└── docs/
    ├── overview/product-planning.md
    ├── architecture/tech-stack.md
    ├── erd/coreerd.md
    ├── requirements/
    │   ├── feature-spec.md
    │   ├── api-spec.md               # REST API 명세
    │   ├── event-spec.md             # Kafka 이벤트 명세
    │   └── grpc-spec.md              # gRPC 명세
    ├── db/                           # 서비스별 DDL SQL
    │   ├── user-service.sql
    │   ├── routine-service.sql
    │   ├── challenge-service.sql
    │   ├── chat-service.sql
    │   └── notification-service.sql
    └── decisions/                    # ADR-0001 ~ ADR-0025
```

### libs 의존성

| 서비스 | 의존하는 libs |
|---|---|
| registry-service | 없음 (순수 Eureka Server, Spring Boot Starter만 사용) |
| gateway | common-core, common-web, common-observability |
| 나머지 서비스 5개 | common-core, common-web, common-observability, proto |

> Gateway는 Spring WebFlux 기반 — common-web 적용 가능 여부 구현 시 검토

---

## 3. 서비스별 DB 및 포트

| 서비스 | HTTP 포트 | gRPC 포트 | PostgreSQL DB | 주요 테이블 |
|---|:---:|:---:|---|---|
| registry-service | 8761 | — | — | — |
| user-service | 8081 | 9081 | routinely_user | users, refresh_tokens |
| routine-service | 8082 | 9082 | routinely_routine | routine_templates, routine_executions, feeds, reactions, *_outbox |
| challenge-service | 8083 | 9083 | routinely_challenge | challenges, challenge_members, *_outbox |
| chat-service | 8084 | 9084 | routinely_chat | chat_rooms, chat_messages, *_outbox, *_inbox |
| notification-service | 8085 | 9085 | routinely_notification | notification_schedules, notification_history, *_inbox |

---

## 4. 서비스 내부 패키지 구조

모든 서비스는 레이어드 아키텍처(Layered + Hexagonal 스타일)로 통일한다.

```
com.routinely.{service}/
├── domain/                   # 핵심 도메인 (Entity, VO, Domain Service, Repository Interface)
├── application/              # Use Case / Service (비즈니스 로직 조합)
├── infrastructure/           # 외부 의존성 구현체
│   ├── persistence/          # JPA Repository 구현, QueryDSL
│   ├── kafka/                # Producer(Outbox Worker), Consumer(Inbox + @RetryableTopic)
│   ├── grpc/                 # gRPC Server / Client Stub
│   └── pgmq/                 # PGMQ Worker (notification-service 전용)
├── presentation/             # 외부 진입점
│   ├── rest/                 # REST Controller, Request/Response DTO
│   └── grpc/                 # gRPC Service 구현 (proto 기반)
└── {Service}Application.java
```

---

## 5. 코딩 컨벤션

### 공통 응답 포맷

모든 REST 응답은 `ApiResponse<T>` (common-core)로 통일한다.

```java
// 성공 (data 있음)
ApiResponse.ok("챌린지 생성에 성공했습니다.", data)
// → { "success": true, "message": "...", "data": {...} }

// 성공 (data 없음)
ApiResponse.ok("탈퇴가 완료되었습니다.")
// → { "success": true, "message": "..." }

// 실패
ApiResponse.fail("CHALLENGE_NOT_FOUND", "챌린지를 찾을 수 없습니다.")
// → { "success": false, "message": "...", "errorCode": "..." }
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

- **패키지**: `com.routinely.{service}.{layer}` (소문자, 복수형 금지)
- **클래스**: PascalCase — `RoutineExecutionService`, `ChallengeController`
- **메서드/변수**: camelCase — `findActiveMembers()`, `routineTemplateId`
- **상수**: UPPER_SNAKE_CASE — `MAX_RETRY_COUNT`
- **DB 컬럼**: snake_case — `created_at`, `user_id`
- **Kafka 토픽**: `{도메인}.{집합체}.{과거형동사}` — `routine.execution.completed`
- **에러 코드**: `UPPER_SNAKE_CASE` 도메인 접두사 포함 — `CHALLENGE_NOT_FOUND`

### 예외 처리 전략

예외 처리는 `common-core`(예외 클래스)와 `common-web`(AOP + ExceptionHandler)에 중앙화한다.

#### 예외 클래스 계층 (common-core)

```java
// ErrorCode enum — 에러 코드 / 메시지 / HTTP 상태를 한 곳에서 관리
public enum ErrorCode {
    // 공통
    VALIDATION_FAILED("VALIDATION_FAILED", "유효성 검사에 실패했습니다.", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED("UNAUTHORIZED", "인증이 필요합니다.", HttpStatus.UNAUTHORIZED),

    // Challenge
    CHALLENGE_NOT_FOUND("CHALLENGE_NOT_FOUND", "챌린지를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    CHALLENGE_ALREADY_JOINED("CHALLENGE_ALREADY_JOINED", "이미 참여한 챌린지입니다.", HttpStatus.CONFLICT),

    // Routine
    ROUTINE_NOT_FOUND("ROUTINE_NOT_FOUND", "루틴을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),

    // ... 서비스별 추가
    ;

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}

// BusinessException — 도메인 예외 단일 클래스
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String customMessage) {
        super(customMessage);
        this.errorCode = errorCode;
    }
}
```

miri-miri처럼 도메인별 예외 클래스를 다수 만들지 않는다.
`BusinessException(ErrorCode.CHALLENGE_NOT_FOUND)` 한 가지 방식으로 통일한다.

#### AOP 유효성 검사 Advice (common-web)

컨트롤러 메서드 파라미터에 `BindingResult`가 있을 때 자동으로 유효성 오류를 감지해 예외를 던진다.
컨트롤러에서 `bindingResult.hasErrors()` 체크 코드를 작성하지 않아도 된다.

```java
// common-web: ValidationAdvice.java
@Component
@Aspect
public class ValidationAdvice {

    @Pointcut("@annotation(org.springframework.web.bind.annotation.PostMapping)")
    public void postMapping() {}

    @Pointcut("@annotation(org.springframework.web.bind.annotation.PutMapping)")
    public void putMapping() {}

    @Pointcut("@annotation(org.springframework.web.bind.annotation.PatchMapping)")
    public void patchMapping() {}

    @Around("postMapping() || putMapping() || patchMapping()")
    public Object validationAdvice(ProceedingJoinPoint pjp) throws Throwable {
        for (Object arg : pjp.getArgs()) {
            if (arg instanceof BindingResult br && br.hasErrors()) {
                Map<String, String> errors = new LinkedHashMap<>();
                br.getFieldErrors().forEach(e -> errors.put(e.getField(), e.getDefaultMessage()));
                throw new ValidationException(errors);
            }
        }
        return pjp.proceed();
    }
}
```

```java
// common-core: ValidationException.java
@Getter
public class ValidationException extends RuntimeException {
    private final Map<String, String> errors;

    public ValidationException(Map<String, String> errors) {
        super(ErrorCode.VALIDATION_FAILED.getMessage());
        this.errors = errors;
    }
}
```

컨트롤러 사용 예:

```java
// BindingResult를 파라미터에 추가하기만 하면 — 체크 코드 불필요
@PostMapping("/challenges")
public ResponseEntity<ApiResponse<CreateChallengeResponse>> createChallenge(
        @RequestBody @Valid CreateChallengeRequest request,
        BindingResult bindingResult) {       // AOP가 자동 처리
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("챌린지 생성에 성공했습니다.", challengeService.create(request)));
}
```

#### 전역 ExceptionHandler (common-web)

```java
// common-web: GlobalExceptionHandler.java
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 유효성 검사 실패 (AOP에서 던짐)
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiResponse<?>> handleValidation(ValidationException e) {
        log.warn("Validation failed: {}", e.getErrors());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.VALIDATION_FAILED.getCode(), e.getMessage(), e.getErrors()));
    }

    // 비즈니스 예외 (서비스 레이어에서 던짐)
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<?>> handleBusiness(BusinessException e) {
        log.warn("Business exception: {}", e.getMessage());
        ErrorCode ec = e.getErrorCode();
        return ResponseEntity.status(ec.getHttpStatus())
                .body(ApiResponse.fail(ec.getCode(), e.getMessage()));
    }

    // 예상치 못한 예외
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleException(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.fail("INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다."));
    }
}
```

유효성 검사 실패 응답 포맷:

```json
{
  "success": false,
  "message": "유효성 검사에 실패했습니다.",
  "errorCode": "VALIDATION_FAILED",
  "data": {
    "email": "이메일 형식이 올바르지 않습니다.",
    "password": "비밀번호는 8자 이상이어야 합니다."
  }
}
```

#### 모듈별 위치 요약

| 클래스 | 모듈 | 패키지 |
|---|---|---|
| `ErrorCode` (enum) | common-core | `com.routinely.core.exception` |
| `BusinessException` | common-core | `com.routinely.core.exception` |
| `ValidationException` | common-core | `com.routinely.core.exception` |
| `ValidationAdvice` | common-web | `com.routinely.web.aop` |
| `GlobalExceptionHandler` | common-web | `com.routinely.web.handler` |

### Entity 작성 규칙

```java
@Entity
@Table(name = "challenges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // JPA 기본 생성자 — 외부 직접 생성 방지
@AllArgsConstructor
@Builder
public class Challenge extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChallengeStatus status;

    // 상태 변경 메서드는 엔티티 내부에 정의
    public void end() {
        this.status = ChallengeStatus.ENDED;
    }
}
```

- `@Setter` 사용 금지 — 상태 변경은 의미 있는 메서드(`end()`, `activate()`)로 표현
- `@Builder` + `@NoArgsConstructor(PROTECTED)` 조합 사용
- `extends BaseEntity` — `createdAt`, `updatedAt` 자동 관리

### Service 계층 규칙

```java
// 인터페이스 + Impl 항상 분리
public interface ChallengeService {
    ChallengeDto.CreateResponse create(ChallengeDto.CreateRequest request, Long userId);
    void join(Long challengeId, Long userId);
}

@Service
@Transactional(readOnly = true)   // 클래스 기본값 — 조회 최적화
@RequiredArgsConstructor
@Slf4j
public class ChallengeServiceImpl implements ChallengeService {

    private final ChallengeRepository challengeRepository;

    @Override
    @Transactional   // 쓰기 메서드는 반드시 오버라이드 — 빠뜨리면 readOnly 트랜잭션으로 INSERT 시도
    public ChallengeDto.CreateResponse create(ChallengeDto.CreateRequest request, Long userId) {
        Challenge challenge = Challenge.builder()
                .title(request.getTitle())
                .build();
        challengeRepository.save(challenge);
        return ChallengeDto.CreateResponse.from(challenge);
    }

    @Override
    @Transactional
    public void join(Long challengeId, Long userId) {
        Challenge challenge = findChallengeByIdOrThrow(challengeId);
        challenge.addMember(userId);
    }

    // findOrThrow 헬퍼 — orElseThrow 중복 제거
    private Challenge findChallengeByIdOrThrow(Long challengeId) {
        return challengeRepository.findById(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
    }
}
```

- Service 인터페이스 + `ServiceImpl` 클래스 항상 분리
- 클래스 레벨에 `@Transactional(readOnly = true)` 기본 적용
- **쓰기 메서드에 `@Transactional` 오버라이드 필수** — 빠뜨리면 readOnly 트랜잭션으로 INSERT 시도하여 오류 발생
- `findXxxByIdOrThrow()` private 헬퍼 메서드로 `orElseThrow` 중복 제거

### DTO 작성 규칙

도메인별로 중첩 static class로 묶어 관리한다. 파일 수를 줄이고 응집도를 높인다.

```java
// presentation/rest/dto/ChallengeDto.java
public class ChallengeDto {

    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)  // Jackson 역직렬화용 기본 생성자 필수
    public static class CreateRequest {
        @NotBlank(message = "제목은 필수입니다.")
        private String title;

        @NotNull
        private LocalDate startDate;
    }

    @Getter
    @Builder
    public static class CreateResponse {
        private Long   challengeId;
        private String title;
        private String status;
    }

    @Getter
    @Builder
    public static class SummaryResponse {
        private Long   challengeId;
        private String title;
        private int    memberCount;
    }
}
```

사용 시: `ChallengeDto.CreateRequest`, `ChallengeDto.SummaryResponse`

### Request DTO 유효성 검사

유효성 검사는 **Controller 레이어(Request DTO)에서만** 수행한다. Service에서 중복 검사하지 않는다.

#### Bean Validation 어노테이션 사용 기준

```java
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public static class CreateRequest {

    @NotBlank(message = "제목은 필수입니다.")          // null + "" + " " 모두 거부 (String 전용)
    @Size(max = 100, message = "제목은 100자 이하여야 합니다.")
    private String title;

    @NotBlank(message = "설명은 필수입니다.")
    @Size(max = 500, message = "설명은 500자 이하여야 합니다.")
    private String description;

    @NotNull(message = "시작일은 필수입니다.")          // null만 거부 (String 외 타입에 사용)
    @FutureOrPresent(message = "시작일은 오늘 이후여야 합니다.")
    private LocalDate startDate;

    @NotNull(message = "종료일은 필수입니다.")
    @Future(message = "종료일은 미래여야 합니다.")
    private LocalDate endDate;

    @NotNull(message = "최대 인원은 필수입니다.")
    @Min(value = 2, message = "최소 2명 이상이어야 합니다.")
    @Max(value = 100, message = "최대 100명까지 가능합니다.")
    private Integer maxMembers;

    @NotNull(message = "공개 여부는 필수입니다.")
    private Boolean isPublic;
}
```

#### 주요 어노테이션 정리

| 어노테이션 | 대상 타입 | 용도 |
|---|---|---|
| `@NotNull` | 모든 타입 | null 거부 |
| `@NotBlank` | String | null / 빈 문자열 / 공백 문자열 거부 |
| `@NotEmpty` | String, Collection | null / 빈 값 거부 (공백 허용) |
| `@Size(min, max)` | String, Collection | 길이 범위 제한 |
| `@Min(value)` | 숫자 | 최솟값 |
| `@Max(value)` | 숫자 | 최댓값 |
| `@Positive` | 숫자 | 양수 (0 제외) |
| `@PositiveOrZero` | 숫자 | 0 이상 |
| `@Email` | String | 이메일 형식 |
| `@Pattern(regexp)` | String | 정규식 |
| `@Future` | 날짜 | 미래 날짜만 |
| `@FutureOrPresent` | 날짜 | 오늘 이후 |
| `@Past` | 날짜 | 과거 날짜만 |

> `@NotBlank` vs `@NotNull`: String 필드는 `@NotBlank` 사용. 숫자, Boolean, 날짜 등은 `@NotNull` 사용.

#### 유효성 메시지 작성 규칙

- **한국어**로 작성
- `"필드명 + 조건"` 형태로 명확하게
- `message` 속성 항상 직접 지정 — 기본 메시지(`must not be blank`) 사용 금지

```java
// 나쁜 예
@NotBlank
private String title;

// 좋은 예
@NotBlank(message = "제목은 필수입니다.")
private String title;
```

#### 유효성 검사 흐름

```
클라이언트 요청
    └── Controller (@RequestBody @Valid + BindingResult)
          └── ValidationAdvice AOP (BindingResult.hasErrors() 감지)
                └── ValidationException 발생
                      └── GlobalExceptionHandler → 400 응답
                            └── { errorCode: "VALIDATION_FAILED", data: { "title": "제목은 필수입니다." } }
```

**Service에서 유효성 재검사 금지** — Controller에서 이미 검증된 값이 내려오므로 중복 검사하지 않는다.

### Controller 작성 규칙

```java
@RestController
@RequestMapping("/api/v1/challenges")
@RequiredArgsConstructor
public class ChallengeController {

    private final ChallengeService challengeService;

    // userId는 Gateway가 추가한 X-User-Id 헤더에서 수신 — JWT 재파싱 불필요
    @PostMapping
    public ResponseEntity<ApiResponse<ChallengeDto.CreateResponse>> create(
            @RequestBody @Valid ChallengeDto.CreateRequest request,
            BindingResult bindingResult,               // AOP가 자동 처리 — 체크 코드 불필요
            @RequestHeader("X-User-Id") Long userId) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("챌린지 생성에 성공했습니다.",
                        challengeService.create(request, userId)));
    }

    @GetMapping("/{challengeId}")
    public ResponseEntity<ApiResponse<ChallengeDto.DetailResponse>> get(
            @PathVariable Long challengeId,
            @RequestHeader("X-User-Id") Long userId) {

        return ResponseEntity.ok(
                ApiResponse.ok("챌린지 조회에 성공했습니다.",
                        challengeService.get(challengeId, userId)));
    }
}
```

- userId는 항상 `@RequestHeader("X-User-Id")`로 수신 — 서비스에서 JWT 파싱 금지
- `BindingResult`는 파라미터 선언만 — AOP가 자동 처리
- 반환 타입: `ResponseEntity<ApiResponse<T>>`

### 상수 관리

Kafka 토픽명, 헤더 키 등 상수는 interface로 묶어 관리한다.

```java
// common-core: KafkaTopics.java
public interface KafkaTopics {
    String ROUTINE_EXECUTION_COMPLETED  = "routine.execution.completed";
    String ROUTINE_NOTIFICATION_SCHEDULED = "routine.notification.scheduled";
    String CHALLENGE_MEMBER_JOINED      = "challenge.member.joined";
    String CHALLENGE_MEMBER_LEFT        = "challenge.member.left";
    String CHAT_MESSAGE_CREATED         = "chat.message.created";
}

// common-core: HeaderConstants.java
public interface HeaderConstants {
    String USER_ID        = "X-User-Id";
    String GATEWAY_SECRET = "X-Gateway-Secret";
}
```

### Lombok 레이어별 조합

| 레이어 | 조합 |
|---|---|
| Entity | `@Getter` + `@NoArgsConstructor(PROTECTED)` + `@Builder` |
| Request DTO | `@Getter` + `@NoArgsConstructor(PROTECTED)` |
| Response DTO | `@Getter` + `@Builder` |
| Service / Component | `@RequiredArgsConstructor` + `@Slf4j` |
| Controller | `@RequiredArgsConstructor` |
| Config | `@RequiredArgsConstructor` |

- **Entity에 `@Setter` 사용 금지** — 상태 변경은 메서드로 표현
- **Response DTO에 `@NoArgsConstructor` 불필요** — 서버에서 직접 생성하므로 기본 생성자 없어도 됨
- **Request DTO에 `@Builder` 불필요** — 테스트 외 클라이언트가 직접 JSON으로 전달

### Repository 규칙

쿼리 복잡도에 따라 사용 방법을 구분한다.

```java
// 1. Spring Data JPA 메서드명 — 단순 조건 조회
public interface ChallengeRepository extends JpaRepository<Challenge, Long> {
    List<Challenge> findByStatus(ChallengeStatus status);
    Optional<Challenge> findByIdAndStatus(Long id, ChallengeStatus status);
}

// 2. @Query — 중간 복잡도 (JOIN 1~2개, 집계)
public interface ChallengeRepository extends JpaRepository<Challenge, Long>,
        ChallengeRepositoryCustom {
    @Query("SELECT c FROM Challenge c WHERE c.status = :status AND c.endDate >= :today")
    List<Challenge> findActiveChallenges(@Param("status") ChallengeStatus status,
                                         @Param("today") LocalDate today);
}

// 3. QueryDSL — 복잡한 동적 쿼리 (다중 조건, 페이지네이션)
@Repository
@RequiredArgsConstructor
public class ChallengeRepositoryImpl implements ChallengeRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<Challenge> searchChallenges(ChallengeSearchCondition cond, Pageable pageable) {
        List<Challenge> content = queryFactory
                .selectFrom(challenge)
                .where(
                    statusEq(cond.getStatus()),
                    titleContains(cond.getKeyword())
                )
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        long total = queryFactory.select(challenge.count()).from(challenge)
                .where(statusEq(cond.getStatus()), titleContains(cond.getKeyword()))
                .fetchOne();

        return new PageImpl<>(content, pageable, total);
    }

    private BooleanExpression statusEq(ChallengeStatus status) {
        return status != null ? challenge.status.eq(status) : null;
    }
}
```

**기준:**
- 단순 조회 → JPA 메서드명
- JOIN / 집계 → `@Query`
- 동적 조건 / 페이지네이션 / 서브쿼리 → QueryDSL

### Outbox 코드 패턴

Kafka publish는 반드시 Outbox 테이블을 경유한다. `kafkaTemplate.send()` 직접 호출 금지.

#### Outbox Entity

```java
@Entity
@Table(name = "challenge_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChallengeOutbox extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String topic;

    @Column(nullable = false)
    private String partitionKey;   // Kafka 파티션 키

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;        // JSON 직렬화된 이벤트

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status = OutboxStatus.PENDING;

    public static ChallengeOutbox of(String topic, String partitionKey, String payload) {
        ChallengeOutbox outbox = new ChallengeOutbox();
        outbox.topic        = topic;
        outbox.partitionKey = partitionKey;
        outbox.payload      = payload;
        return outbox;
    }

    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
    }
}
```

#### 도메인 저장 + Outbox INSERT (같은 트랜잭션)

```java
@Transactional
public void join(Long challengeId, Long userId) {
    Challenge challenge = findChallengeByIdOrThrow(challengeId);
    ChallengeMembers member = challenge.addMember(userId);
    challengeMemberRepository.save(member);

    // 같은 트랜잭션 내 Outbox INSERT — DB 커밋과 동시에 보장
    ChallengeMemberJoinedEvent event = new ChallengeMemberJoinedEvent(challengeId, userId);
    String payload = objectMapper.writeValueAsString(event);
    outboxRepository.save(ChallengeOutbox.of(KafkaTopics.CHALLENGE_MEMBER_JOINED,
                                              String.valueOf(challengeId), payload));
}
```

#### Outbox Worker

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class ChallengeOutboxWorker {

    private final ChallengeOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 1000)   // 1초 polling
    @Transactional
    public void publish() {
        List<ChallengeOutbox> pending = outboxRepository.findTop100ByStatusOrderByCreatedAtAsc(
                OutboxStatus.PENDING);

        for (ChallengeOutbox outbox : pending) {
            try {
                kafkaTemplate.send(outbox.getTopic(), outbox.getPartitionKey(), outbox.getPayload());
                outbox.markPublished();
            } catch (Exception e) {
                log.error("Outbox publish failed - id: {}", outbox.getId(), e);
            }
        }
    }
}
```

### 로그 작성 규칙

| 레벨 | 사용 시점 | 예시 |
|---|---|---|
| `ERROR` | 예상치 못한 예외, 외부 시스템 장애 | gRPC 호출 실패, Outbox 발행 실패 |
| `WARN` | 비즈니스 예외 (복구 가능), 재시도 | BusinessException, 유효성 검사 실패 |
| `INFO` | 주요 비즈니스 이벤트, 서비스 기동 | 챌린지 생성, 루틴 완료, Outbox 발행 |
| `DEBUG` | 개발 디버깅용 (local/dev 프로파일에서만) | 쿼리 파라미터, 중간 값 |

```java
// Controller — 로그 작성 불필요 (GlobalExceptionHandler가 처리)

// Service — 주요 이벤트만 INFO
@Transactional
public void join(Long challengeId, Long userId) {
    Challenge challenge = findChallengeByIdOrThrow(challengeId);
    challenge.addMember(userId);
    log.info("Challenge joined - challengeId: {}, userId: {}", challengeId, userId);
}

// Outbox Worker — 발행 실패는 ERROR
} catch (Exception e) {
    log.error("Outbox publish failed - id: {}, topic: {}", outbox.getId(), outbox.getTopic(), e);
}

// gRPC Client — 호출 실패는 ERROR
} catch (StatusRuntimeException e) {
    log.error("gRPC call failed - service: challenge, rpc: CheckMembership, status: {}",
              e.getStatus().getCode(), e);
}
```

- **파라미터는 로그 메시지에 포함** — `log.info("...", value)` 형태로 (문자열 연결 금지)
- **민감 정보(비밀번호, 토큰) 로그 출력 금지**
- **traceId / userId는 Micrometer와 MDC 필터가 자동 주입** — 직접 넣지 않아도 됨

### `@Profile` 활용 패턴

```java
// local 프로파일에서 GatewayAuthFilter 비활성화 (ADR-0019)
@Component
@Profile("!local")
public class GatewayAuthFilter extends OncePerRequestFilter { ... }

// prod 프로파일에서만 S3 실제 클라이언트 등록
@Bean
@Profile("prod")
public FileStorage s3FileStorage(AmazonS3 s3Client) {
    return new S3FileStorage(s3Client);
}

// local/dev 프로파일에서 로컬 파일 저장소 사용
@Bean
@Profile("!prod")
public FileStorage localFileStorage() {
    return new LocalFileStorage();
}
```

```yaml
# 프로파일별 application.yml
# application-local.yml    — 로컬 IntelliJ 직접 실행
# application-dev.yml      — Docker Compose 환경
# application-prod.yml     — AWS 배포 환경

# application-local.yml 예시
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/routinely_challenge
gateway:
  secret: ${GATEWAY_SECRET:local-secret}   # 기본값 설정으로 환경변수 없이 기동 가능
```

### 테스트 작성 규칙

#### 테스트 계층

```
단위 테스트 (Unit Test)       → Service 레이어, 도메인 로직
슬라이스 테스트 (Slice Test)   → Repository, Controller 레이어 개별 검증
통합 테스트 (Integration Test) → 전체 흐름 (실제 DB 사용)
```

#### 1. 단위 테스트 — Service 레이어

Mockito로 외부 의존성을 Mock 처리. 빠른 피드백, 순수 비즈니스 로직 검증.

```java
// application/ChallengeServiceImplTest.java
@ExtendWith(MockitoExtension.class)
class ChallengeServiceImplTest {

    @InjectMocks
    private ChallengeServiceImpl challengeService;

    @Mock
    private ChallengeRepository challengeRepository;

    @Mock
    private ChallengeOutboxRepository outboxRepository;

    @Test
    @DisplayName("챌린지 참여 성공")
    void join_success() {
        // given
        Long challengeId = 1L;
        Long userId = 10L;
        Challenge challenge = createChallenge(challengeId);
        given(challengeRepository.findById(challengeId)).willReturn(Optional.of(challenge));

        // when
        challengeService.join(challengeId, userId);

        // then
        verify(outboxRepository, times(1)).save(any(ChallengeOutbox.class));
    }

    @Test
    @DisplayName("존재하지 않는 챌린지 참여 시 예외 발생")
    void join_challengeNotFound() {
        // given
        given(challengeRepository.findById(anyLong())).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> challengeService.join(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("챌린지를 찾을 수 없습니다");
    }

    // 테스트용 픽스처 — private 헬퍼
    private Challenge createChallenge(Long id) {
        return Challenge.builder()
                .title("테스트 챌린지")
                .status(ChallengeStatus.WAITING)
                .build();
    }
}
```

#### 2. 슬라이스 테스트 — Repository

`@DataJpaTest` + H2 인메모리 DB. JPA 쿼리 정확성만 검증.

```java
// infrastructure/persistence/ChallengeRepositoryTest.java
@DataJpaTest
@ActiveProfiles("test")
class ChallengeRepositoryTest {

    @Autowired
    private ChallengeRepository challengeRepository;

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("상태로 챌린지 목록 조회")
    void findByStatus() {
        // given
        challengeRepository.save(Challenge.builder().title("A").status(ChallengeStatus.ACTIVE).build());
        challengeRepository.save(Challenge.builder().title("B").status(ChallengeStatus.ENDED).build());
        em.flush();
        em.clear();

        // when
        List<Challenge> result = challengeRepository.findByStatus(ChallengeStatus.ACTIVE);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("A");
    }
}
```

#### 3. 슬라이스 테스트 — Controller

`@WebMvcTest` + MockMvc. HTTP 요청/응답 형식, 상태코드, 유효성 검사 검증.

```java
// presentation/rest/ChallengeControllerTest.java
@WebMvcTest(ChallengeController.class)
class ChallengeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChallengeService challengeService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("챌린지 생성 성공 — 201 반환")
    void create_success() throws Exception {
        // given
        ChallengeDto.CreateRequest request = new ChallengeDto.CreateRequest("30일 챌린지", LocalDate.now());
        ChallengeDto.CreateResponse response = ChallengeDto.CreateResponse.builder()
                .challengeId(1L).title("30일 챌린지").build();
        given(challengeService.create(any(), anyLong())).willReturn(response);

        // when & then
        mockMvc.perform(post("/api/v1/challenges")
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.challengeId").value(1L));
    }

    @Test
    @DisplayName("제목 없이 챌린지 생성 시 400 반환")
    void create_titleBlank_400() throws Exception {
        ChallengeDto.CreateRequest request = new ChallengeDto.CreateRequest("", LocalDate.now());

        mockMvc.perform(post("/api/v1/challenges")
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }
}
```

#### 테스트 네이밍 규칙

```java
// 클래스명: 대상클래스 + Test
class ChallengeServiceImplTest { }
class ChallengeRepositoryTest { }
class ChallengeControllerTest { }

// 메서드명: 한글 @DisplayName 필수, 메서드명은 간결하게
@Test
@DisplayName("챌린지 참여 성공")
void join_success() { }

@Test
@DisplayName("이미 참여한 챌린지 재참여 시 예외 발생")
void join_alreadyJoined_throwsException() { }
```

#### given / when / then 구조 필수

```java
@Test
@DisplayName("...")
void methodName() {
    // given
    // (준비)

    // when
    // (실행)

    // then
    // (검증)
}
```

#### 테스트용 설정 파일

```yaml
# src/test/resources/application-test.yml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: create-drop
    defer-datasource-initialization: true
```

#### 테스트 작성 우선순위

| 우선순위 | 대상 | 이유 |
|---|---|---|
| 1순위 | Service 단위 테스트 | 핵심 비즈니스 로직. 빠르고 격리됨 |
| 2순위 | Controller 슬라이스 테스트 | 유효성 검사, 응답 포맷 검증 |
| 3순위 | Repository 슬라이스 테스트 | 복잡한 QueryDSL 쿼리 검증 |
| 4순위 | 통합 테스트 | 전체 흐름 검증 (느리므로 핵심 시나리오만) |

### 이벤트 명명 원칙

- 이벤트는 **과거형** — "이 일이 발생했다"는 사실만 전달
- 이벤트에 **명령을 담지 않는다** — 소비자가 무엇을 할지는 소비자가 결정

---

## 6. API 설계 원칙

### Gateway 라우팅 규칙

| 경로 패턴 | 라우팅 대상 |
|---|---|
| `/api/v1/auth/**` | user-service |
| `/api/v1/users/**` | user-service |
| `/api/v1/challenges/**` | challenge-service |
| `/api/v1/routine-templates/**` | routine-service |
| `/api/v1/routines/**`, `/api/v1/routine-executions/**` | routine-service |
| `/api/v1/feed/**`, `/api/v1/statistics/**` | routine-service |
| `/api/v1/chat/**` | chat-service |
| `/ws/chat` | chat-service (WebSocket) |
| `/api/v1/notifications/**` | notification-service |
| `/api/v1/home` | gateway (WebClient + Mono.zip() 병렬 집계) |

### 인증

- 인증 필요 API: `Authorization: Bearer {accessToken}` 헤더 필수
- JWT 검증은 Gateway 필터에서 중앙 처리 — 각 서비스는 검증하지 않음 (ADR-0006)
- Swagger UI: `local` / `dev` 프로파일에서만 활성화 (prod: `springdoc.api-docs.enabled: false`)

---

## 7. Kafka 토픽 목록

| 토픽 | Publisher | Subscriber | Partition Key |
|---|---|---|---|
| `routine.execution.completed` | routine-service | notification-service | `userId` |
| `routine.notification.scheduled` | routine-service | notification-service | `userId` |
| `challenge.member.joined` | challenge-service | chat-service, notification-service | `challengeId` |
| `challenge.member.left` | challenge-service | chat-service | `challengeId` |
| `chat.message.created` | chat-service | chat-service (전 인스턴스) | `roomId` |

**공통 페이로드 필드**: `eventId` (UUID, 멱등성 키), `occurredAt` (ISO 8601)

### Outbox 발행 규칙

- `kafkaTemplate.send()` 직접 호출 금지 — 반드시 outbox 테이블을 거친다
- `@Transactional` 내: 도메인 저장 + `*_outbox` INSERT 동시 수행
- Outbox Worker가 PENDING 레코드 polling → Kafka publish → PUBLISHED 갱신

### Inbox 처리 규칙

- 소비자는 `eventId` + `*_inbox` 테이블로 중복 처리 방지
- `@RetryableTopic` + DLT 전략으로 소비자 복원력 확보 (ADR-0014)

---

## 8. gRPC 호출 관계

| Caller | Server | RPC | 호출 시점 |
|---|---|---|---|
| chat-service | challenge-service | `CheckMembership` | 채팅방 입장 / 메시지 발송 전 멤버 검증 |
| routine-service | challenge-service | `GetChallengeContext` | 챌린지 루틴 실행 완료 처리 전 유효성 검증 |

**원칙**:
- 단방향 의존 — 호출 방향은 항상 소비자 → 제공자, **순환 호출 금지**
- Proto 파일 위치: `libs/proto/src/main/proto/`
- gRPC 비즈니스 판단은 Status Code가 아닌 **응답 필드**로 전달
- 실패: `NOT_FOUND` → HTTP 404 / `INTERNAL` → HTTP 500 / `DEADLINE_EXCEEDED` → HTTP 503

---

## 9. DB 마이그레이션 규칙

- DDL 원본 파일: `docs/db/{service-name}.sql`
- 컬럼 타입: 시각 정보는 **TIMESTAMPTZ** (timezone 포함) 사용
- 기본 타임스탬프 컬럼: `created_at TIMESTAMPTZ DEFAULT now() NOT NULL`, `updated_at TIMESTAMPTZ DEFAULT now() NOT NULL`
- `id` 컬럼: `BIGINT GENERATED ALWAYS AS IDENTITY` (sequence 자동 생성)
- 인덱스 명명: `idx_{테이블약어}_{컬럼명}` — `idx_nh_user_id`
- 제약 조건 명명: `pk_{테이블명}`, `fk_{테이블명}_{참조테이블}`, `ck_{테이블약어}_{설명}`
- `*_outbox` / `*_inbox` 테이블: 각 서비스 DB 내 존재, Outbox/Inbox 패턴 구현용
- `notification_settings` 테이블: **MVP에서 구현하지 않는다** (v2 도입)

---

## 10. 로컬 개발 환경

```bash
# 인프라 기동 (PostgreSQL, Redis, Kafka)
./scripts/local-up.sh

# 인프라 종료
./scripts/local-down.sh

# DB 마이그레이션
./scripts/db-migrate.sh

# 특정 서비스 실행 (예: routine-service)
./gradlew :services:routine-service:bootRun

# 전체 빌드
./gradlew build

# 특정 서비스 테스트
./gradlew :services:challenge-service:test
```

---

## 11. 주의사항

- **서비스 기동 순서**: registry-service → gateway → 각 서비스 (registry-service가 먼저 기동되어야 함)
- **Gateway는 WebFlux 기반** — common-web(MVC 기반) 의존 여부 구현 시 검토 필요
- **gRPC 포트 = HTTP 포트 + 1000** — 모든 서비스 동일 규칙 (registry-service 제외)
- **Kafka publish는 반드시 Outbox 경유** — `kafkaTemplate.send()` 직접 호출 금지
- **JWT 검증 로직을 각 서비스에 중복 구현하지 않는다** — Gateway에서만 처리
- **알림 예약 Next-One 원칙** — 사용자당 PENDING 레코드는 1건만 유지, `NOTIFICATION_SCHEDULES` upsert
- **환경 변수**: `.env`는 `.gitignore` 처리, `.env.example`만 커밋
- **Swagger**: prod 프로파일에서 `springdoc.api-docs.enabled: false` 필수
- **로그**: JSON 구조 로그를 stdout으로 출력 (`logstash-logback-encoder`), traceId/spanId는 Micrometer가 자동 주입

---

## 12. 참고 문서

| 문서 | 경로 |
|---|---|
| 개발 프로세스 & 로드맵 | `docs/overview/development-process.md` |
| 기술 스택 | `docs/architecture/tech-stack.md` |
| gRPC 구현 가이드 | `docs/architecture/grpc-guide.md` |
| 관찰 가능성 | `docs/architecture/observability.md` |
| REST API 명세 | `docs/requirements/api-spec.md` |
| Kafka 이벤트 명세 | `docs/requirements/event-spec.md` |
| gRPC 명세 (API 스펙) | `docs/requirements/grpc-spec.md` |
| ERD | `docs/erd/coreerd.md` |
| ADR 목록 | `docs/decisions/` |
