# Routinely 개발 프로세스

## 전체 진행 단계

```
Step 1  요구사항 정의         ✅ 완료
Step 2  설계 및 아키텍처 결정  ✅ 완료
Step 3  문서화                ✅ 완료
Step 4  개발 환경 구성        🔲 진행 예정
Step 5  백엔드 개발           🔲 진행 예정
Step 6  프론트엔드 개발       🔲 진행 예정
Step 7  통합 및 테스트        🔲 진행 예정
Step 8  마무리                🔲 진행 예정
```

---

## Step 4 — 개발 환경 구성

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
> common-web (java-library + api)을 의존하면 Spring Web, Validation, JPA가 서비스에 자동 전달.
> 따라서 서비스 Initializr에서 Spring Web, Spring Data JPA, Validation은 선택 불필요.
> gRPC는 공식 Spring gRPC (`org.springframework.grpc`) 사용. Initializr에서 "Spring gRPC Server" / "Spring gRPC Client" 선택.

| 모듈 | Initializr 선택 의존성 | 수동 추가 |
|---|---|---|
| `libs/common-core` | (Initializr 불필요, 수동 생성) | `api spring-boot-starter-validation`, `api spring-boot-starter-data-jpa` |
| `libs/common-web` | (Initializr 불필요, 수동 생성) | `api common-core`, `api spring-boot-starter-web`, `impl spring-boot-starter-aop` |
| `libs/common-observability` | (Initializr 불필요, 수동 생성) | `micrometer-tracing-bridge-brave`, `zipkin-reporter-brave`, `micrometer-registry-prometheus` |
| `libs/proto` | (Initializr 불필요, 수동 생성) | protobuf 플러그인 (grpc-guide.md 참고) |
| `services/registry-service` | Eureka Server | — |
| `services/gateway` | Gateway, Eureka Discovery Client | `common-core`, `common-observability` |
| `services/user-service` | PostgreSQL Driver, Flyway | `common-web`, `common-observability`, `spring-security-crypto` |
| `services/routine-service` | PostgreSQL Driver, Flyway, Kafka, **Spring gRPC Server, Spring gRPC Client** | `common-web`, `common-observability`, `proto` |
| `services/challenge-service` | PostgreSQL Driver, Flyway, Kafka, **Spring gRPC Server** | `common-web`, `common-observability`, `proto` |
| `services/chat-service` | WebSocket, PostgreSQL Driver, Flyway, Kafka, **Spring gRPC Client** | `common-web`, `common-observability`, `proto` |
| `services/notification-service` | PostgreSQL Driver, Flyway, Kafka | `common-web`, `common-observability` |

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
Docker Compose가 자동으로 같은 디렉터리의 `.env`를 읽어 `${VARIABLE}` 치환에 사용한다.

```bash
# infra/.env.example
DB_PASSWORD=        # PostgreSQL POSTGRES_PASSWORD 로 사용
JWT_SECRET=         # Gateway JWT 서명 검증 키
GATEWAY_SECRET=     # X-Gateway-Secret 헤더 값 (ADR-0019)
GRAFANA_PASSWORD=   # Grafana 관리자 비밀번호 (기본: admin)
```

---

## Step 5 — 백엔드 개발

### 전체 개발 순서

```
Phase 0  libs 공통 모듈     common-core → common-web → common-observability → proto
Phase 1  인프라 서비스       registry-service → gateway
Phase 2  핵심 서비스        user-service
Phase 3  도메인 서비스       challenge-service → routine-service
Phase 4  실시간 서비스       chat-service → notification-service
```

> **핵심 원칙**: Kafka / gRPC / Outbox는 기본 CRUD 완성 후 붙인다.
> libs 4개가 먼저 완성되어야 서비스 개발이 수월하다.

---

### Phase 0 — libs 공통 모듈

#### common-core

```
com.routinely.core/
├── exception/
│   ├── ErrorCode.java          # enum: code, message, httpStatus
│   ├── BusinessException.java  # RuntimeException + ErrorCode
│   └── ValidationException.java
├── response/
│   └── ApiResponse.java        # ok() / fail() static factory
└── entity/
    └── BaseEntity.java         # @MappedSuperclass: createdAt, updatedAt
```

**`BaseEntity.java`** (miri-miri 패턴)
```java
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
```

**`ApiResponse.java`**
```java
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiResponse<T> {
    private boolean success;
    private String  message;
    private String  errorCode;
    private T       data;

    public static <T> ApiResponse<T> ok(String message, T data) { ... }
    public static <T> ApiResponse<T> ok(String message) { ... }
    public static <T> ApiResponse<T> fail(String errorCode, String message) { ... }
    public static <T> ApiResponse<T> fail(String errorCode, String message, T data) { ... }
}
```

#### common-web

```
com.routinely.web/
├── aop/
│   └── ValidationAdvice.java    # @Around POST/PUT/PATCH → BindingResult 자동 처리
├── handler/
│   └── GlobalExceptionHandler.java  # @RestControllerAdvice
└── filter/
    └── GatewayAuthFilter.java    # X-Gateway-Secret 검증 (ADR-0019)
                                  # @Profile("!local") 로 local 프로파일에서 비활성화
```

> **GatewayAuthFilter**: Spring Security 없이 `OncePerRequestFilter` 하나로 X-Gateway-Secret 헤더 검증

#### common-observability

```
com.routinely.observability/
└── config/
    └── ObservabilityConfig.java   # Micrometer Tracing Bean 설정
```

`logback-spring.xml` 은 각 서비스에서 공통으로 사용할 템플릿을 여기에 두거나,
각 서비스 `src/main/resources/`에 복사하여 사용한다.

#### proto

`.proto` 파일 작성 및 빌드 설정은 [`docs/architecture/grpc-guide.md`](../architecture/grpc-guide.md) 참고.

---

### Phase 1 — 인프라 서비스

#### registry-service

파일 2개면 완성.

```java
@SpringBootApplication
@EnableEurekaServer
public class RegistryServiceApplication { ... }
```

```yaml
server.port: 8761
eureka.client.register-with-eureka: false
eureka.client.fetch-registry: false
```

**완료 기준:** `http://localhost:8761` 접속 → Eureka 대시보드 확인

#### gateway

구현 순서:
1. `application.yml` 라우팅 규칙 (`lb://service-name`)
2. `JwtAuthenticationFilter` — JWT 검증 + `X-User-Id` / `X-Gateway-Secret` 헤더 추가
3. `RateLimitFilter` — Redis Token Bucket (나중에 추가)
4. Home Aggregation 엔드포인트 (`Mono.zip()`) — 서비스 완성 후 추가

**완료 기준:** gateway 기동 → Eureka 대시보드에 `gateway` 등록 확인

---

### Phase 2 — user-service

의존 서비스 없음 → 가장 먼저 구현.

**구현 순서:**
1. `User` 엔티티 + `UserRepository`
3. 회원가입 — BCrypt 비밀번호 해시, JWT Access/Refresh 토큰 발급
4. 로그인 / 로그아웃 / 토큰 갱신
5. 프로필 조회 / 수정 / 아바타 업로드 (S3)

**완료 기준:** Postman으로 회원가입 → 로그인 → JWT 발급 확인

> Spring Security 미사용. `spring-security-crypto`의 `BCryptPasswordEncoder`만 사용 (ADR-0019).

---

### Phase 3 — challenge-service / routine-service

#### challenge-service (gRPC 서버)

**구현 순서:**
1. 챌린지 CRUD (생성 / 조회 / 수정 / 종료)
3. 멤버 관리 (참여 / 탈퇴 / 추방)
4. **gRPC 서버 구현** — `CheckMembership`, `GetChallengeContext`
5. Outbox Worker + Kafka 발행 (`challenge.member.joined`, `challenge.member.left`)

**완료 기준:** `grpcurl`로 `CheckMembership` RPC 호출 성공 확인

#### routine-service (gRPC 서버 + 클라이언트)

**구현 순서:**
1. 루틴 템플릿 CRUD
3. 루틴 인스턴스 관리 (생성 / 조회 / 비활성화)
4. 루틴 실행 기록 (CRUD + 완료 처리)
5. **gRPC 클라이언트** — challenge-service `GetChallengeContext` 호출
6. 피드 카드 + 반응(이모지)
7. 통계 (일별 / 주별 / 월별)
8. Outbox Worker + Kafka 발행

> `challenge-service` gRPC 서버가 먼저 완성되어야 한다.

---

### Phase 4 — chat-service / notification-service

#### chat-service

**구현 순서:**
1. 채팅방 REST API (목록 / 생성)
3. **WebSocket/STOMP** — 연결, 메시지 발송, 구독
4. **gRPC 클라이언트** — challenge-service `CheckMembership` 호출
5. Kafka Consumer — `chat.message.created` (다중 인스턴스 브로드캐스트, ADR-0016)
6. Kafka Consumer — `challenge.member.joined` / `challenge.member.left`

#### notification-service

**구현 순서:**
1. 알림 이력 조회 REST API + SSE 연결
3. Kafka Consumer — `routine.execution.completed`, `challenge.member.joined`
4. PGMQ 워커 — `sendAt` 기반 알림 발송 (Next-One Chaining, ADR-0017)

---

### 각 서비스 내부 개발 순서 (공통 패턴)

```
1. application.yml 기본 설정 (DB 접속, Eureka, 포트)
   └─ Flyway는 서비스 기동 시 src/main/resources/db/migration/V1__init.sql을 자동 실행
2. domain/        Entity + Repository 인터페이스
3. infrastructure/persistence/  JPA Repository 구현
4. application/   Service 인터페이스 + Impl (비즈니스 로직)
5. presentation/rest/  Controller + DTO
6. 기본 CRUD 로컬 테스트 (Postman)
7. Kafka / gRPC / Outbox 추가
```

### miri-miri에서 가져온 코딩 패턴

| 패턴 | 적용 위치 | 설명 |
|---|---|---|
| `findOrThrow` private 헬퍼 | 각 ServiceImpl | `orElseThrow` 중복 제거 |
| Service 인터페이스 + Impl 분리 | 모든 서비스 | `ChallengeService` + `ChallengeServiceImpl` |
| 클래스 레벨 `@Transactional(readOnly = true)` | 모든 ServiceImpl | 쓰기 메서드만 `@Transactional` 오버라이드 |
| `@Scheduled` Outbox Worker | 각 서비스 kafka/ | 1초 polling, PENDING → PUBLISHED |
| DTO 중첩 static class | 각 서비스 rest/ | `ChallengeDto.CreateRequest` 형태 |

---

## Step 6 — 프론트엔드 개발

user-service 완성 이후 백엔드와 **병행 개발** 가능.

- React + TypeScript + Vite
- React Query (서버 상태 관리)
- Zustand (전역 UI 상태)
- STOMP (채팅 WebSocket)
- Tailwind CSS + shadcn/ui

---

## Step 7 — 통합 및 테스트

| 단계 | 내용 |
|------|------|
| Unit Test | 서비스 레이어, 도메인 로직 단위 테스트 |
| Integration Test | DB · Kafka · Redis 연동 테스트 (`@SpringBootTest`) |
| E2E Test | 주요 사용자 시나리오 기반 엔드 투 엔드 테스트 |

---

## Step 8 — 마무리

- **API 문서 최종 정리**: Swagger UI, AsyncAPI, protoc-gen-doc
- **Grafana 대시보드 완성** 및 AlertManager 규칙 정교화
- **배포 환경 구성**: AWS EC2 + Docker
- **CI/CD 파이프라인**: GitHub Actions (PR: build/test, main merge: docker build/push)
