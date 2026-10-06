# Routinely Backend

Java 21 / Spring Boot 4.0.5 / Gradle 멀티모듈 MSA. Group ID `com.routinely`.
**이 파일 하나로 백엔드 작업에 필요한 규칙이 모두 담긴다.** 상위 디렉토리 파일에 의존하지 않는다 —
Codex는 git 저장소 루트 위로 올라가지 못하기 때문이다.

## 0. 이 프로젝트의 위치와 AI 협업 규칙 — 먼저 읽는다

Routinely는 사용자의 **2027년 상반기 이직 포트폴리오**에 들어가는 개인 프로젝트다. 사용자는 Claude Code와
Codex로 빠르게 개발하되, **면접관이 아무 파일이나 열어 물어봐도 직접 설명할 수 있어야** 한다.

- **MVP 마감 2026-11-30**, 그 뒤 LLM 기능 1개(기능은 사용자가 고른다). 그다음은 기능을 늘리지 않고 면접 대비
- **MVP 범위** — 루틴 생성 → 챌린지 참여 → 인증 → 피드·랭킹·달력. **채팅 · 알림 · 홈 집계 · 비공개 챌린지·초대는 v2**다.
  이슈 마일스톤 `MVP` / `v2`로 구분되어 있다. 범위·순서의 단일 출처는 워크스페이스 `docs/roadmap.md`
  (이 저장소 밖 `../../docs/roadmap.md` — 열 수 없으면 사용자에게 묻는다)

### 반드시 지킨다

1. **설계는 사용자가 정한다.** 기능마다 **API · 테이블 · 주요 흐름 · 이유 한 줄**은 개발 전에 사용자가 직접 정한다.
   AI는 **선택지와 장단점만** 낸다. `WORKPLAN.md`에도 "결정"이 아니라 **"선택지 — 사용자 결정 대기"** 로 쓰고,
   사용자가 고른 것만 "결정"으로 옮긴 뒤 구현한다. 구현 중에 설계 갈림길이 나오면 **멈추고 묻는다.**
2. **새 기능을 제안하지 않는다.** 범위는 줄이는 쪽으로만 돕는다. MVP 밖(v2) 기능은 요청받지 않는 한 구현하지 않는다.
3. **역할 분담 — 구현은 Claude Code, 리뷰는 Codex.**
   - **한 워크트리는 한 번에 한 도구만 수정한다.** 넘길 때는 `WORKPLAN.md`에 상태를 적는다
   - **리뷰를 요청받으면 파일을 고치지 않는다.** 지적만 낸다 — 위치(`파일:줄`) · 무엇이 틀렸나 · 재현 조건 · 심각도.
     반영 여부는 사용자가 정한다
   - 구현을 명시적으로 요청받았을 때만 코드를 고친다

### 이슈 하나의 진행 순서

| 단계 | 누가 | 할 일 |
|---|---|---|
| ① 설계 | AI가 선택지 → **사용자가 결정** | WORKPLAN에 선택지와 장단점. 사용자가 "API · 테이블 · 흐름 · 이유" 네 줄을 정한다 |
| ② 구현 | Claude Code | 계층별로 작게 커밋. 테스트가 완료 기준 |
| ③ 교차 리뷰 | Codex | diff를 리뷰하고 지적만 낸다. 사용자가 읽고 반영 여부를 정한다 |
| ④ 이해 확인 | AI가 묻고 → **사용자가 답한다** | 구현을 마치면 diff 기준 **면접식 질문**(왜 · 다른 방법은 · 장애가 나면)을 낸다. **답은 쓰지 않는다** — 사용자가 먼저 답한다 |
| ⑤ 직접 손대기 | **사용자** | A등급만 — 핵심 로직 하나를 `TODO(human)`으로 남긴다(§6 학습 모드) |
| ⑥ PR의 "왜" | **사용자** | PR 본문의 "왜 이렇게 했나" 절은 **비워 두고** 자리만 만든다 |

### 이해 깊이 — A · B · C

면접 가치에 따라 사용자가 들일 시간을 나눈다. 에이전트는 이슈를 시작할 때 등급을 WORKPLAN 맨 위에 적는다.

| 등급 | 기준 | 이 저장소에서 | ④ 질문 | ⑤ TODO(human) |
|---|---|---|---|---|
| **A 깊게** | 꼬리 질문이 3단계까지 올 곳 | 루틴 실행 기록 · Outbox/Inbox · 랭킹 이벤트(`revision` · 캡) · gRPC 검증(#58) · 챌린지 이벤트 소비(#157 · #158) · LLM 연동 | 5개 | 1개 |
| **B 흐름만** | "어떻게 동작하나요" 수준 | 피드 · 통계 · 오늘의 루틴 · 챌린지 CRUD · 사용자 조회 gRPC | 2~3개 | 없음 |
| **C 훑기** | 거의 안 물어볼 곳 | 설정 · DTO · 단순 검증 · 리팩터링 · 보안 헤더 같은 소품 | 없음 | 없음 |

## 0-1. 작업 규칙

### 시작하기 전에

**저장소 루트에 `WORKPLAN.md`가 있으면 먼저 읽는다.** 지금 브랜치의 목표·등급·선택지(또는 사용자가 정한 결정)·실행 계획이
거기 있다. **"선택지 — 사용자 결정 대기"가 남아 있으면 구현하지 말고 사용자에게 먼저 묻는다.** `.gitignore` 대상이라 워크트리 안에만 존재하고 커밋에 실리지 않는다.
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
- 커밋·PR에 AI 생성 표기(`Co-Authored-By`, "Generated with …" 등)를 넣지 않는다.

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
| Job Queue | 예약 테이블 폴링 | 확장 없음. 알림 예약 전용 (ADR-0009 · **ADR-0045** — PGMQ는 쓰지 않는다) |
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
| `notification-service` | 알림 스케줄러·워커 (테이블 폴링) | 8085 | 9085 | `routinely_notification` |

> gRPC 포트 = HTTP 포트 + 1000. DB는 서비스별 독립 PostgreSQL — **서비스 간 직접 DB 접근 금지.**

인프라: Redis(Rate Limiting·ZSET 랭킹·캐시) / Kafka(도메인 이벤트) / 예약 테이블 폴링(서비스 내부 Job) /
Zipkin·Loki·Prometheus·Grafana(추적·로그·메트릭)

### 통신 전략

**원칙: Command → gRPC / Event → Kafka / Job → DB 폴링(ADR-0045) / Client 요청 → HTTP**

| 구간 | 방식 | 사용 시점 |
|---|---|---|
| 클라이언트 ↔ 서버 | HTTP REST | 일반 API |
| 실시간 채팅 | WebSocket / STOMP | 채팅 송수신 |
| 실시간 알림 | SSE | 서버 → 클라이언트 단방향 |
| 서비스 간 동기 | gRPC | 즉시 응답이 필요한 Command |
| 서비스 간 비동기 | Kafka + Outbox | 도메인 이벤트 |
| 서비스 내부 비동기 | 예약 테이블 폴링 | 알림 예약 Job (ADR-0045) |
| Gateway 홈 집계 | WebClient + `Mono.zip()` | `/api/v1/home` 병렬 집계 |

## 4. 모듈 구조 규칙

`libs/`(공통 라이브러리) + `services/`(서비스) 멀티모듈. 정확한 목록은 `settings.gradle`을 본다.

- `common-core` — JPA·Web 의존이 없는 순수 도메인. **여기에 JPA/Web 의존을 추가하지 않는다.**
- `common-jpa` — `BaseEntity`, JPA Auditing 설정
- `common-web` — 필터, MDC, `GlobalExceptionHandler` (`common-core`를 전이 포함)
- `common-observability` / `common-storage` / `proto`

의존 규칙: gateway-service는 JPA가 불필요하므로 `common-jpa`를 넣지 않는다.
gRPC를 쓰는 서비스(routine·challenge, 알림 구현 후 notification)만 `proto`에 의존한다. chat은 **멤버 판정에 gRPC를 쓰지 않는다**(ADR-0046) — 발신자 닉네임만 user-service `GetUsers`(#153)를 부른다.

### 서비스 내부 패키지

`com.routinely.{service}.{layer}` — `domain` / `application` / `infrastructure` / `presentation`.
새 코드는 이 4계층 중 하나에 넣는다. `infrastructure`는 `persistence` · `kafka` · `grpc` · `scheduler`로,
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
**기본값은 등급을 따른다 — A등급 이슈는 라이트, B·C등급은 전부 구현**(§0 이해 깊이). 사용자가 따로 말하면 그걸 따른다.

## 7. 문서 갱신 규칙

**코드를 바꾸면 같은 커밋에서 문서도 바꾼다.** 아래 셋은 특히 놓치기 쉽다.

### 7-1. 제품 규약이 바뀌면 — `docs/product/policies.md`

이슈를 구현하다 **규약이 정해지거나 뒤집히면** 워크스페이스 루트
`../../docs/product/policies.md`(워크트리에서는 두 단계 위)의 해당 항목과 **상태를 함께 갱신한다.**
🟡(미정)가 🟢(확정)이 되는 순간을 놓치지 않는다.

새 결정이 ADR급이면 `docs/decisions/`에 ADR을 쓰고 policies.md에서 링크한다.

### 7-2. 이벤트·gRPC가 바뀌면 — `docs/architecture/service-interaction-map.md`

REST는 Swagger가 담당하지만 **이벤트와 gRPC는 코드를 다 열어보기 전에는 전경이 보이지 않는다.**
Mermaid 관계도로 유지하는 문서가 있으니 **같은 커밋에서 고친다.**

| 상황 | 고칠 곳 |
|---|---|
| 토픽 신설·폐기, 구독자 추가·제거 | `service-interaction-map.md` §1 그래프 · §2 매트릭스 |
| 페이로드 필드 변경 | `docs/requirements/event-spec.md`(상세) + 지도 §2 변경 표 |
| RPC 추가·시그니처 변경 | 지도 §3 + `docs/requirements/grpc-spec.md` |
| **양단 배선 완료** | **지도의 상태 배지를 🟡·⬜ → ✅ 로** |
| 흐름이 바뀌는 결정 | 지도 §4 시퀀스 |

> **상태 배지 갱신을 빠뜨리지 않는다.** 이 문서의 값은 "무엇이 아직 안 이어졌는지"가 한눈에 보이는
> 데 있다. 그림만 맞고 배지가 낡으면 오히려 해롭다.

### 7-3. 포트폴리오 — `../../portfolio/`

- **기능 구현을 마치면** `portfolio.md`에 기여 항목을 추가한다
- **새 기술·패턴을 도입하면** `interview-qa.md`에 예상 질문과 꼬리 질문을 함께 적는다
- **기술을 선택했으면** `tech-story.md`에 배경과 트레이드오프를 적는다 (기각한 대안 포함)

단순 디버깅·설정 수정은 건너뛴다.

## 8. 참고 문서

규칙이 아니라 배경·결정·명세다. 필요할 때 읽는다.

**이 저장소** — `docs/decisions/` ADR · `docs/conventions/` 구현 패턴 ·
`docs/requirements/` API·이벤트·gRPC 명세 · `docs/architecture/` · `docs/db/`

> `docs/architecture/service-interaction-map.md` — **이벤트·gRPC 관계도(Mermaid).** 누가 무엇을 발행하고
> 누가 받는지, 어디가 아직 안 이어졌는지를 한 장으로 본다. 갱신 규칙은 §7-2.

**워크스페이스 공유** (`../../docs/`, 워크트리에서는 두 단계 위) — `architecture.md` 전체 아키텍처 ·
`services/` 서비스별 상세 · `patterns/outbox-inbox.md` · `patterns/notification-polling.md`

> **`docs/product/`** — 제품 규약. `policies.md`가 "무엇을 할 수 있고 없는가"의 **단일 출처**다.
> `overview.md`(용어·핵심 흐름) · `screens.md`(화면 명세)와 함께 본다. 갱신 규칙은 §7-1.
