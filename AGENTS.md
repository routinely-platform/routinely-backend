# Routinely Backend

Java 21 / Spring Boot 4.0.5 / Gradle 멀티모듈 MSA. Group ID `com.routinely`.
**이 파일 하나로 백엔드 작업에 필요한 규칙이 모두 담긴다.** 상위 디렉토리 파일에 의존하지 않는다 —
Codex는 git 저장소 루트 위로 올라가지 못하기 때문이다.

## 0. 작업 규칙

### 시작하기 전에

**저장소 루트에 `WORKPLAN.md`가 있으면 먼저 읽는다.** 지금 브랜치의 목표·설계 결정·실행 계획이
거기 있다. `.gitignore` 대상이라 워크트리 안에만 존재하고 커밋에 실리지 않는다.
작업하며 설계가 바뀌면 `WORKPLAN.md`를 갱신한다. 이 파일(`AGENTS.md`)은 건드리지 않는다.

### 워크트리 / 브랜치

- 이슈 하나당 워크트리 하나. `origin/main` 기준으로 생성한다.
- 브랜치명 `{타입}/#{번호}-{한글설명}`, 워크트리 폴더명 `{타입}-{번호}-{영문설명}`.
- 워크트리끼리 파일을 복사해 옮기지 않는다. 공통 변경은 `main`에 넣고 rebase로 전파한다.
- 규칙 파일을 고쳤으면 `main`에 머지된 뒤에 새 워크트리를 만든다. 워크트리는 `origin/main`
  기준으로 생기므로 로컬에만 있으면 반영되지 않는다.

### 커밋 / PR

- 커밋 메시지 `{타입}: #{번호} {이슈 제목}`, PR 본문에 `Closes #{번호}` 필수.
- force push 금지.

### 규칙을 어디에 쓰나

| 내용 | 위치 |
|---|---|
| 백엔드 전반 규칙 | **이 파일** |
| 특정 서비스만의 규칙 | `services/{서비스}/AGENTS.md` (루트와 병합되어 로드된다) |
| 이번 브랜치의 설계·계획 | `WORKPLAN.md` |
| 아키텍처 배경·ADR | `docs/` — 규칙이 아니라 문서다. 여기엔 경로만 적는다 |

이 파일을 브랜치별로 만들지 않는다. 워크트리 루트가 곧 저장소 루트라 경로가 겹치고,
커밋하면 PR에 실려 `main`으로 넘어간다. 브랜치 단위 내용은 `WORKPLAN.md`에 둔다.

## 1. 빌드 · 실행 · 테스트

```bash
./scripts/local-up.sh              # 인프라 기동 (PostgreSQL, Redis, Kafka)
./scripts/local-up.sh --obs        # + Observability 스택
./scripts/local-down.sh

./gradlew :services:user-service:bootRun --args='--spring.profiles.active=local'
./gradlew :services:user-service:test    # 서비스 단위 테스트
./gradlew build                          # 전체 빌드
```

### 프로파일 정책

`local` / `prod`는 환경 프로파일, `observability`는 **애드온이라 단독 사용 불가** — 반드시 조합한다.

| 조합 | 사용 시점 |
|---|---|
| `local` | 평소 로컬 개발 (기본값) |
| `local,observability` | 로컬에서 Observability까지 확인 (`local-up.sh --obs`와 함께) |
| `prod` / `prod,observability` | 배포 |

Swagger UI, DEBUG 로그 등 개발 전용 기능은 `local` 계열에서만 활성화한다.

## 2. 기술 선택과 배경

| 항목 | 선택 | 알아둘 것 |
|---|---|---|
| ORM | Spring Data JPA + QueryDSL | 복잡한 조회는 QueryDSL |
| Security | Spring Security + JWT | **Gateway에서 중앙 검증** (ADR-0006) |
| Service Discovery | Eureka | Config Server 미사용 (ADR-0020) |
| gRPC | grpc-spring-boot-starter | proto는 `libs/proto`에서 중앙 관리 |
| Messaging | Kafka | **Outbox 패턴으로만 발행** (ADR-0012) |
| Job Queue | PGMQ | PostgreSQL 확장, 알림 예약 전용 (ADR-0009) |
| File Storage | AWS S3 | `FileStorage` 인터페이스로 추상화 (`libs/common-storage`) |
| Resilience | Resilience4j | timeout / retry / circuit breaker |
| 관측 | Micrometer Tracing + Zipkin, Alloy → Loki, Prometheus | traceId/spanId 자동 주입 |
| CI/CD | GitHub Actions | PR: build·test / merge: docker build·push |

## 3. 시스템 전경

### 서비스 · 포트 · DB

| 서비스 | 역할 | HTTP | gRPC | DB |
|---|---|:---:|:---:|---|
| `registry-service` | Eureka 서버 | 8761 | — | — |
| `gateway-service` | 라우팅, JWT 검증, Rate Limiting | 8080 | — | — |
| `user-service` | 회원가입 / 로그인 / 프로필 | 8081 | 9081 | `routinely_user` |
| `routine-service` | 루틴 생성 / 수행 기록 / 피드 / 통계 | 8082 | 9082 | `routinely_routine` |
| `challenge-service` | 챌린지 생성 / 참여 / 랭킹 | 8083 | 9083 | `routinely_challenge` |
| `chat-service` | WebSocket·STOMP 채팅 | 8084 | 9084 | `routinely_chat` |
| `notification-service` | 알림 스케줄러·워커 (PGMQ) | 8085 | 9085 | `routinely_notification` |

> gRPC 포트 = HTTP 포트 + 1000. DB는 서비스별 독립 PostgreSQL — **서비스 간 직접 DB 접근 금지.**

인프라: Redis(Rate Limiting·ZSET 랭킹·캐시) / Kafka(도메인 이벤트) / PGMQ(서비스 내부 Job 큐) /
Zipkin·Loki·Prometheus·Grafana(추적·로그·메트릭)

### 통신 전략

**원칙: Command → gRPC / Event → Kafka / Job → PGMQ / Client 요청 → HTTP**

| 구간 | 방식 | 사용 시점 |
|---|---|---|
| 클라이언트 ↔ 서버 | HTTP REST | 일반 API |
| 실시간 채팅 | WebSocket / STOMP | 채팅 송수신 |
| 실시간 알림 | SSE | 서버 → 클라이언트 단방향 |
| 서비스 간 동기 | gRPC | 즉시 응답이 필요한 Command |
| 서비스 간 비동기 | Kafka + Outbox | 도메인 이벤트 |
| 서비스 내부 비동기 | PGMQ | 알림 예약 Job |
| Gateway 홈 집계 | WebClient + `Mono.zip()` | `/api/v1/home` 병렬 집계 |

## 4. 모듈 구조 규칙

`libs/`(공통 라이브러리) + `services/`(서비스) 멀티모듈. 정확한 목록은 `settings.gradle`을 본다.

- `common-core` — JPA·Web 의존이 없는 순수 도메인. **여기에 JPA/Web 의존을 추가하지 않는다.**
- `common-jpa` — `BaseEntity`, JPA Auditing 설정
- `common-web` — 필터, MDC, `GlobalExceptionHandler` (`common-core`를 전이 포함)
- `common-observability` / `common-storage` / `proto`

의존 규칙: gateway-service는 JPA가 불필요하므로 `common-jpa`를 넣지 않는다.
gRPC를 쓰는 서비스(routine·challenge·chat)만 `proto`에 의존한다.

### 서비스 내부 패키지

`com.routinely.{service}.{layer}` — `domain` / `application` / `infrastructure` / `presentation`.
새 코드는 이 4계층 중 하나에 넣는다. `infrastructure`는 `persistence` · `kafka` · `grpc` · `pgmq`로,
`presentation`은 `rest` · `grpc`로 나눈다. 계층 원칙은 `docs/conventions/clean-architecture.md`.

## 5. 코딩 컨벤션

### 응답 / 상태코드

모든 REST 응답은 `ApiResponse<T>`(common-core)로 통일한다.

```java
ApiResponse.ok("챌린지 생성에 성공했습니다.", data)
ApiResponse.ok("탈퇴가 완료되었습니다.")
ApiResponse.fail("CHALLENGE_NOT_FOUND", "...")
```

200 조회·수정·삭제 / 201 생성 / 400 유효성 / 401 인증 / 403 권한 / 404 없음 / 409 중복·충돌

### 네이밍

- 클래스 PascalCase, 메서드·변수 camelCase, 상수 UPPER_SNAKE_CASE, DB 컬럼 snake_case
- Kafka 토픽 `{도메인}.{집합체}.{과거형동사}` — `routine.execution.completed`
- 에러 코드 도메인 접두사 + UPPER_SNAKE_CASE — `CHALLENGE_NOT_FOUND`

### 예외 처리

- **`ErrorCode` enum + `BusinessException` 단일 클래스로 통일 — 도메인별 예외 클래스를 새로 만들지 않는다.**
- 유효성 검사는 **Controller 레이어에서만** 한다. Service에서 중복 검사하지 않는다.
- 전역 처리는 `common-web`의 `GlobalExceptionHandler`가 담당한다. → `docs/conventions/exception-handling.md`

### Entity

- **`@Setter` 금지.** 상태 변경은 의미 있는 메서드(`end()`, `activate()`)로 표현한다.
- `@Builder` + `@NoArgsConstructor(PROTECTED)` 조합, `extends BaseEntity`로 `createdAt`/`updatedAt` 자동 관리.
  → `docs/conventions/entity-repository.md`

### Service

- 인터페이스와 `ServiceImpl`을 항상 분리한다.
- 클래스 레벨에 `@Transactional(readOnly = true)`를 기본으로 걸고,
  **쓰기 메서드마다 `@Transactional`을 오버라이드한다** — 빠뜨리면 readOnly 트랜잭션으로 INSERT를 시도한다.
- `orElseThrow` 중복은 `findXxxByIdOrThrow()` private 헬퍼로 묶는다. → `docs/conventions/service-dto.md`

### DTO / 유효성 검사

- 도메인별 중첩 static class로 묶는다 — `ChallengeDto.CreateRequest`, `ChallengeDto.CreateResponse`
- Request는 `@Getter` + `@NoArgsConstructor(PROTECTED)` (Jackson 역직렬화용 기본 생성자 필수), Response는 `@Getter` + `@Builder`
- String은 `@NotBlank`, 숫자·Boolean·날짜는 `@NotNull`
- **`message` 속성을 항상 한국어로 직접 지정한다.** 기본 메시지(`must not be blank`)를 그대로 두지 않는다.

### Controller

- `userId`는 항상 `@RequestHeader("X-User-Id")`로 받는다. **JWT를 재파싱하지 않는다** (Gateway가 이미 검증).
- **`BindingResult` 파라미터를 선언하지 않는다** — 없어야 Spring이 `MethodArgumentNotValidException`을 던진다.
- 반환 타입은 `ResponseEntity<ApiResponse<T>>`. → `docs/conventions/controller.md`

### Lombok 레이어별 조합

Entity `@Getter`+`@NoArgsConstructor(PROTECTED)`+`@Builder` / Request DTO `@Getter`+`@NoArgsConstructor(PROTECTED)` /
Response DTO `@Getter`+`@Builder` / Service·Component `@RequiredArgsConstructor`+`@Slf4j` /
Controller·Config `@RequiredArgsConstructor`

### Repository

단순 조회는 JPA 메서드명, JOIN·집계는 `@Query`, 동적 조건·페이지네이션은 QueryDSL.
락 전략은 `docs/conventions/locking-strategy.md`.

### Kafka / Outbox

- **`kafkaTemplate.send()`를 직접 호출하지 않는다.** 반드시 Outbox 테이블을 경유한다.
- 도메인 저장과 Outbox INSERT는 **같은 `@Transactional` 안에서** 수행한다.
- 토픽명·헤더 키는 `common-core`의 `KafkaTopics`, `HeaderConstants` 인터페이스로 관리한다.
  → `docs/conventions/outbox-pattern.md`

### 로그

`ERROR` 예상치 못한 예외·외부 시스템 장애 / `WARN` 비즈니스 예외·재시도 /
`INFO` 주요 비즈니스 이벤트 / `DEBUG` 개발 디버깅(`local` 계열 전용)

- 파라미터는 플레이스홀더로 넘긴다 — `log.info("...", value)`. 문자열 연결 금지.
- **비밀번호·토큰 등 민감 정보를 로그에 남기지 않는다.** traceId/userId는 MDC 필터가 자동 주입한다.

### 테스트

| 종류 | 어노테이션 | 대상 |
|---|---|---|
| 단위 | `@ExtendWith(MockitoExtension.class)` | Service, 도메인 로직 |
| 슬라이스 | `@DataJpaTest` | JPA 쿼리 |
| 슬라이스 | `@WebMvcTest` | HTTP 형식, 유효성 검사 |

메서드명 `{동작}_{시나리오}` — `join_success`, `join_alreadyJoined_throwsException`.
`@DisplayName`은 한글로 필수. → `docs/conventions/testing.md`

## 6. 학습 모드

이 프로젝트는 학습 목적을 포함한다. **코드를 전부 작성하지 말고 핵심 로직은 `TODO(human)`으로 남긴다.**

```java
// TODO(human): {구현할 내용 설명}
// HINT: {힌트}
// REFERENCE: {참고 문서 경로}
```

| 빈칸으로 남길 것 | 에이전트가 작성할 것 |
|---|---|
| 비즈니스 로직의 핵심 (알고리즘, 조건 분기, 데이터 변환) | 보일러플레이트 (설정, DTO, Entity 구조) |
| 새로 배우는 기술의 핵심 (gRPC 서비스 정의, Kafka 컨슈머 핸들링) | 패키지·프로젝트 구조, 의존성 설정 |
| 테스트의 assertion | 인터페이스·추상 클래스 정의 |
| 복잡한 SQL (JOIN, 집계) | 구현 가이드 및 힌트 주석 |

강도 조절: `"학습 모드 하드"` 모든 비즈니스 로직 / `"학습 모드 라이트"` 핵심 1개만 / `"전부 구현"` TODO 없이.
**기본값은 라이트.**

## 7. 참고 문서

규칙이 아니라 배경·결정·명세다. 필요할 때 읽는다.

**이 저장소** — `docs/decisions/` ADR · `docs/conventions/` 구현 패턴 ·
`docs/requirements/` API·이벤트·gRPC 명세 · `docs/architecture/` · `docs/db/`

**워크스페이스 공유** (`../../docs/`, 워크트리에서는 두 단계 위) — `architecture.md` 전체 아키텍처 ·
`services/` 서비스별 상세 · `patterns/outbox-inbox.md` · `patterns/pgmq-chaining.md`
