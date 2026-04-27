# Routinely 개발 프로세스

## 전체 진행 단계

```
Step 1  요구사항 정의          ✅ 완료
Step 2  설계 및 아키텍처 결정   ✅ 완료
Step 3  문서화                 ✅ 완료
Step 4  개발 환경 구성         ✅ 완료
Step 5  백엔드 개발            🔄 진행 중  ← Phase 1 완료 / Phase 2 진행 중
Step 6  프론트엔드 개발        🔲 진행 예정
Step 7  통합 및 테스트         🔲 진행 예정
Step 8  마무리                 🔲 진행 예정
```

---

## Step 4 — 개발 환경 구성 ✅

### 4-1. 루트 Gradle 설정

Spring Initializr 사용 없이 루트에서 직접 생성한다.

```
routinely-backend/
├── settings.gradle
├── build.gradle
├── gradlew
├── gradlew.bat
└── gradle/wrapper/
```

**`settings.gradle`**
```groovy
rootProject.name = 'routinely-backend'

include 'libs:common-core'
include 'libs:common-web'
include 'libs:common-observability'
include 'libs:proto'

include 'services:registry-service'
include 'services:gateway'
include 'services:user-service'
include 'services:routine-service'
include 'services:challenge-service'
include 'services:chat-service'
include 'services:notification-service'
```

### 4-2. Spring Initializr 활용 방법

Spring Initializr에서 받은 zip을 통째로 쓰지 않는다.
**`src/` 폴더와 `build.gradle`만 가져온다.**

```
[Spring Initializr 다운로드 후]
  └── xxx.zip 압축 해제
        ├── src/          → services/user-service/src/ 로 복사
        ├── build.gradle  → services/user-service/build.gradle 로 복사
        └── 나머지 전부 버림 (gradlew, settings.gradle, .gitignore 등)
```

**공통 설정값 (모든 모듈 동일)**
- Project: Gradle - Groovy
- Language: Java
- Spring Boot: 4.0.5
- Group: `com.routinely`
- Java: 21
- Packaging: Jar

### 4-3. 모듈별 Initializr 의존성

> Lombok, spring-boot-starter-test는 루트 build.gradle subprojects 블록에서 전체 적용.
> common-web (java-library + api)을 의존하면 Spring Web, Validation이 서비스에 자동 전달.
> gRPC는 공식 Spring gRPC (`org.springframework.grpc`) 사용.

| 모듈 | Initializr 선택 의존성 | 수동 추가 |
|---|---|---|
| `libs/common-core` | (Initializr 불필요, 수동 생성) | `api spring-boot-starter-validation` |
| `libs/common-web` | (Initializr 불필요, 수동 생성) | `api common-core`, `api spring-boot-starter-web` |
| `libs/common-jpa` | (Initializr 불필요, 수동 생성) | `api spring-boot-starter-data-jpa` |
| `libs/common-observability` | (Initializr 불필요, 수동 생성) | `micrometer-tracing-bridge-brave`, `zipkin-reporter-brave`, `micrometer-registry-prometheus` |
| `libs/proto` | (Initializr 불필요, 수동 생성) | protobuf 플러그인 (grpc-guide.md 참고) |
| `services/registry-service` | Eureka Server | — |
| `services/gateway` | Gateway, Eureka Discovery Client | `common-core`, `common-observability` |
| `services/user-service` | PostgreSQL Driver, Flyway | `common-web`, `common-jpa`, `common-observability`, `spring-security-crypto` |
| `services/routine-service` | PostgreSQL Driver, Flyway, Kafka, **Spring gRPC Server, Spring gRPC Client** | `common-web`, `common-jpa`, `common-observability`, `proto` |
| `services/challenge-service` | PostgreSQL Driver, Flyway, Kafka, **Spring gRPC Server** | `common-web`, `common-jpa`, `common-observability`, `proto` |
| `services/chat-service` | WebSocket, PostgreSQL Driver, Flyway, Kafka, **Spring gRPC Client** | `common-web`, `common-jpa`, `common-observability`, `proto` |
| `services/notification-service` | PostgreSQL Driver, Flyway, Kafka | `common-web`, `common-jpa`, `common-observability` |

### 4-4. Docker Compose 구성

`infra/` 아래 두 파일과 설정 폴더로 구성한다.

**`docker-compose.yml`** — 앱 인프라 (항상 실행)
```
PostgreSQL :5432 (1 인스턴스 × 5 DB), Redis :6379, Kafka :29092 (KRaft)
```

> Zookeeper는 Kafka 3.3+의 KRaft 모드로 대체 → 컨테이너 불필요.
> registry-service는 Spring Boot 앱이므로 Docker Compose가 아닌 직접 실행.
> Spring Boot 서비스 → Kafka 접속: `localhost:29092` (EXTERNAL 리스너)

**`docker-compose.observability.yml`** — 관찰 가능성 (필요 시 실행)
```
Zipkin :9411, Prometheus :9090, Loki :3100, Grafana Alloy, Grafana :3000
```

**`config/`** — 컨테이너 설정 파일
```
config/postgres/init.sql          # 5개 서비스 DB 초기화
config/prometheus/prometheus.yml  # 스크랩 타겟 (host.docker.internal)
config/alloy/config.alloy         # 로그 수집 파이프라인
config/grafana/provisioning/      # Grafana 데이터소스 자동 등록
```

```bash
# 앱 인프라만
./scripts/local-up.sh

# 관찰 가능성 포함
./scripts/local-up.sh --obs

# 종료 (볼륨 유지)
./scripts/local-down.sh

# 종료 + 데이터 초기화
./scripts/local-down.sh -v
```

### 4-5. 환경 변수

`infra/.env` 파일은 `.gitignore` 처리, `infra/.env.example`만 커밋한다.

```bash
# infra/.env.example
DB_PASSWORD=        # PostgreSQL POSTGRES_PASSWORD 로 사용
JWT_SECRET=         # Gateway JWT 서명 검증 키
GATEWAY_SECRET=     # X-Gateway-Secret 헤더 값 (ADR-0019)
GRAFANA_PASSWORD=   # Grafana 관리자 비밀번호 (기본: admin)

# Gateway Rate Limiting
RATE_LIMIT_CAPACITY=              # 윈도우 내 최대 허용 요청 수 (기본: 30)
RATE_LIMIT_REFILL_PERIOD_SECONDS= # 윈도우 크기 초 단위 (기본: 60)
RATE_LIMIT_TRUSTED_PROXIES=       # 신뢰할 프록시 IP (prod 필수, 미설정 시 기동 실패)
RATE_LIMIT_FAIL_OPEN_ON_REDIS_ERROR= # Redis 장애 시 요청 통과 여부 (기본: false)
```

---

## Step 5 — 백엔드 개발 🔄

### 전체 개발 순서

```
Phase 0  libs 공통 모듈     common-core ✅ → common-web ✅ → common-jpa ✅ → common-observability 🔲 → proto 🔲
Phase 1  인프라 서비스       registry-service ✅ → gateway ✅ (JWT·Rate Limiting·Circuit Breaker 완료)
Phase 2  핵심 서비스        user-service 🔄 (기본 CRUD 완료 / 프로필 이미지 🔲)
Phase 3  도메인 서비스       challenge-service 🔲 → routine-service 🔲
Phase 4  실시간 서비스       notification-service 🔲 → chat-service 🔲
공통     횡단 관심사         Circuit Breaker 🔄 (gateway ✅ / routine·chat 🔲) → 홈 집계 엔드포인트 🔲
```

> **핵심 원칙**: Kafka / gRPC / Outbox는 기본 CRUD 완성 후 붙인다.

---

### Phase 0 — libs 공통 모듈

#### common-core ✅

```
com.routinely.core/
├── exception/
│   ├── ErrorCode.java
│   ├── ErrorStatus.java
│   └── BusinessException.java
├── response/
│   └── ApiResponse.java        # ok() / fail() static factory
└── constant/
    ├── HeaderConstants.java
    └── KafkaTopics.java
```

#### common-web ✅

```
com.routinely.web/
├── handler/
│   └── GlobalExceptionHandler.java  # @RestControllerAdvice
└── filter/
    └── GatewayAuthFilter.java       # X-Gateway-Secret 검증 (ADR-0019)
```

#### common-jpa ✅

```
com.routinely.jpa/
├── entity/
│   └── BaseEntity.java         # @MappedSuperclass: createdAt, updatedAt
└── config/
    └── JpaAuditingConfig.java
```

#### common-observability 🔲

```
com.routinely.observability/
└── config/
    └── ObservabilityConfig.java   # Micrometer Tracing Bean 설정
```

각 서비스 `src/main/resources/logback-spring.xml` 템플릿도 여기서 관리한다.

#### proto 🔲

`.proto` 파일 작성 및 빌드 설정은 [`docs/architecture/grpc-guide.md`](../architecture/grpc-guide.md) 참고.

> **주의**: challenge-service gRPC 서버 개발 전에 반드시 완료해야 한다.

---

### Phase 1 — 인프라 서비스

#### registry-service ✅

**완료 기준:** `http://localhost:8761` Eureka 대시보드 확인

#### gateway ✅

| 항목 | 상태 |
|---|---|
| 라우팅 설정 (`application.yml lb://`) | ✅ |
| JWT 인증 필터 (`JwtAuthenticationFilter`) | ✅ |
| Rate Limiting (Redis Fixed Window Counter) | ✅ |
| Circuit Breaker (Resilience4j) | ✅ (PR #88) |
| 홈 집계 엔드포인트 (`/api/v1/home`) | 🔲 — 전 서비스 완성 후 |

**완료 기준:** gateway 기동 → Eureka 대시보드에 `gateway` 등록 확인 ✅

---

### Phase 2 — user-service 🔄

의존 서비스 없음.

**구현 순서:**
1. `User` 엔티티 + `UserRepository` ✅ (PR #90)
2. 회원가입 — BCrypt 비밀번호 해시 ✅ (PR #90)
3. 로그인 / JWT Access·Refresh 토큰 발급 / 토큰 갱신 / 로그아웃 ✅ (PR #94)
4. 프로필 조회 / 닉네임 수정 / 회원 탈퇴 ✅ (PR #96)
5. 프로필 이미지 업로드 / 수정 / 삭제 🔲 (이슈 #95)

**완료 기준:** Postman으로 회원가입 → 로그인 → JWT 발급 확인 ✅

> Spring Security 미사용. `spring-security-crypto`의 `BCryptPasswordEncoder`만 사용 (ADR-0019).

---

### Phase 3 — challenge-service / routine-service 🔲

#### challenge-service (gRPC 서버) 🔲

> `proto` 모듈이 먼저 완성되어야 한다.

**구현 순서:**
1. 챌린지 CRUD (생성 / 조회 / 수정 / 종료)
2. 멤버 관리 (참여 / 탈퇴 / 추방 / 초대 링크)
3. **gRPC 서버** — `CheckMembership`, `GetChallengeContext`
4. Outbox Worker + Kafka 발행 (`challenge.member.joined`, `challenge.member.left`)
5. Redis ZSET 기반 랭킹 API

**완료 기준:** `grpcurl`로 `CheckMembership` RPC 호출 성공 확인

#### routine-service (gRPC 클라이언트) 🔲

> `challenge-service` gRPC 서버가 먼저 완성되어야 한다.
> 사진 업로드를 위한 S3 공통 구현 (`FileStorage` 인터페이스 + S3 구현체)을 먼저 작성한다.

**구현 순서:**
1. 루틴 템플릿 CRUD
2. 루틴 인스턴스 관리 (반복 규칙 기반 자동 생성 / 비활성화)
3. 루틴 실행 기록 (완료 처리, S3 사진 업로드, 메모)
4. **gRPC 클라이언트** — challenge-service `GetChallengeContext` 호출
5. 피드 (개인 피드, 그룹 피드, 인증 카드, 이모지 리액션)
6. 통계 (일/주/월 달성률, 스트릭, 그룹 평균)
7. Outbox Worker + Kafka 발행 (`routine.execution.completed`)

---

### Phase 4 — notification-service / chat-service 🔲

#### notification-service 🔲

**구현 순서:**
1. 알림 이력 조회 REST API + SSE 연결
2. Kafka Consumer — `routine.execution.completed`, `challenge.member.joined`
3. PGMQ 워커 — `sendAt` 기반 알림 발송 (Next-One Chaining, ADR-0017)

#### chat-service 🔲

**구현 순서:**
1. 채팅방 REST API (목록 / 생성)
2. **WebSocket/STOMP** — 연결, 메시지 발송, 구독
3. **gRPC 클라이언트** — challenge-service `CheckMembership` 호출
4. Kafka Consumer — `chat.message.created` (다중 인스턴스 브로드캐스트, ADR-0016)
5. Kafka Consumer — `challenge.member.joined` / `challenge.member.left`

---

### 횡단 관심사

| 작업 | 상태 | 시점 |
|---|---|---|
| Circuit Breaker (Resilience4j) — gateway | ✅ (PR #88) | 완료 |
| Circuit Breaker (Resilience4j) — routine-service / chat-service | 🔲 | Phase 4 완료 후 |
| gateway 홈 집계 엔드포인트 (`/api/v1/home`, WebClient + Mono.zip()) | 🔲 | 전 서비스 완성 후 |
| 각 서비스 common-observability 적용 | 🔲 | 서비스 완성 시마다 순차 적용 |

---

### 각 서비스 내부 개발 순서 (공통 패턴)

```
1. application.yml 기본 설정 (DB 접속, Eureka, 포트)
   └─ Flyway: 기동 시 db/migration/V1__init.sql 자동 실행
2. domain/        Entity + Repository 인터페이스
3. infrastructure/persistence/  JPA Repository 구현
4. application/   Service 인터페이스 + Impl
5. presentation/rest/  Controller + DTO
6. 기본 CRUD 로컬 테스트 (Postman)
7. Kafka / gRPC / Outbox 추가
```

### 코딩 패턴

| 패턴 | 적용 위치 | 설명 |
|---|---|---|
| `findOrThrow` private 헬퍼 | 각 ServiceImpl | `orElseThrow` 중복 제거 |
| Service 인터페이스 + Impl 분리 | 모든 서비스 | `ChallengeService` + `ChallengeServiceImpl` |
| 클래스 레벨 `@Transactional(readOnly = true)` | 모든 ServiceImpl | 쓰기 메서드만 `@Transactional` 오버라이드 |
| `@Scheduled` Outbox Worker | 각 서비스 kafka/ | 1초 polling, PENDING → PUBLISHED |
| DTO 중첩 static class | 각 서비스 rest/ | `ChallengeDto.CreateRequest` 형태 |

---

## Step 6 — 프론트엔드 개발 🔲

### 개발 전략

백엔드 서비스 전체 완성을 기다리지 않는다.
**API 하나가 완성되는 시점에 해당 화면을 바로 붙인다** (기능 슬라이스 병행).

> user-service 완성 이후부터 백엔드와 병행 가능.

### 기술 스택

- React + TypeScript + Vite
- React Query (서버 상태)
- Zustand (전역 UI 상태)
- STOMP (채팅 WebSocket)
- Tailwind CSS + shadcn/ui

---

### Stage 0 — 프론트엔드 기반 (user-service 완성 후)

백엔드 진행: challenge-service

| 작업 | 비고 |
|---|---|
| 프로젝트 세팅 (Vite + React + TypeScript + Tailwind) | |
| Axios 인터셉터 (토큰 자동 첨부 / 401 → Refresh 갱신 / 실패 → 로그아웃) | gateway JWT 흐름과 직결 |
| React Router 구조 및 Protected Route | 미인증 → /login 리디렉션 |
| 공통 레이아웃 (헤더, 네비게이션 shell) | |
| 로그인 / 회원가입 페이지 | user-service API |
| 로그아웃 처리 | |
| 프로필 조회 / 닉네임 수정 페이지 | user-service API |

---

### Stage 1 — challenge 화면 (challenge-service 백엔드와 병행)

| 백엔드 API 완성 시점 | 프론트엔드 작업 |
|---|---|
| 챌린지 CRUD API | 챌린지 생성 / 목록 / 상세 화면 |
| 멤버 참여 / 초대 API | 초대 링크, 멤버 관리 화면 |
| 랭킹 API | 랭킹 화면 |
| gRPC 서버 | 화면 없음 (내부용) |

---

### Stage 2 — routine 화면 (routine-service 백엔드와 병행)

| 백엔드 API 완성 시점 | 프론트엔드 작업 |
|---|---|
| 루틴 CRUD API | 루틴 생성 / 목록 / 수정 화면 |
| 실행 기록 API | 오늘의 루틴 체크 화면, 사진 업로드 |
| 개인 피드 API | 개인 피드 화면 |
| 그룹 피드 API | 그룹 피드, 인증 카드, 이모지 리액션 화면 |
| 통계 API | 달성률 / 스트릭 / 그룹 평균 화면 |

---

### Stage 3 — 알림 화면 (notification-service 백엔드와 병행)

| 백엔드 API 완성 시점 | 프론트엔드 작업 |
|---|---|
| SSE 연결 API | 헤더 알림 아이콘 + SSE 연결 훅 |
| 알림 이력 조회 API | 알림 목록 드로어/페이지 |

---

### Stage 4 — 채팅 화면 (chat-service 백엔드와 병행)

| 백엔드 API 완성 시점 | 프론트엔드 작업 |
|---|---|
| 채팅방 REST API | 채팅방 목록 화면 |
| STOMP 메시지 발송 | 채팅 UI (말풍선, 입력창) |

---

### Stage 5 — 홈 화면 (전 서비스 완성 후)

| 작업 |
|---|
| `/api/v1/home` 홈 집계 API 연동 |
| 홈 화면 (루틴 요약, 챌린지 현황, 알림 미리보기) |

---

## Step 7 — 통합 및 테스트 🔲

| 단계 | 내용 |
|---|---|
| Unit Test | 서비스 레이어, 도메인 로직 단위 테스트 |
| Integration Test | DB · Kafka · Redis 연동 (`@SpringBootTest`) |
| E2E Test | 주요 사용자 시나리오 엔드 투 엔드 테스트 |

---

## Step 8 — 마무리 🔲

- **API 문서 최종 정리**: Swagger UI, AsyncAPI, protoc-gen-doc
- **Grafana 대시보드 완성** 및 AlertManager 규칙 정교화
- **배포 환경 구성**: AWS EC2 + Docker
- **CI/CD 파이프라인**: GitHub Actions (PR: build/test, main merge: docker build/push)
