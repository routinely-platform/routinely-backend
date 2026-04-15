# Directory Architecture

## 최종 채택 구조

```
routinely-backend/
│
├── build.gradle                      # 루트 빌드 스크립트 (공통 설정)
├── settings.gradle                   # 멀티모듈 include 정의
├── gradlew / gradlew.bat
├── gradle/wrapper/
├── .gitignore
├── .github/
│   └── workflows/                    # GitHub Actions CI/CD
│
├── docs/                             # 프로젝트 문서
│   ├── overview/
│   │   ├── product-planning.md       # 프로젝트 기획 (목적/기능/도메인)
│   │   └── development-process.md    # 개발 순서 및 프로세스
│   ├── architecture/
│   │   ├── directory-architecture.md # 디렉토리 구조 및 libs 설계 (이 파일)
│   │   ├── tech-stack.md             # 기술 스택 및 아키텍처 설계
│   │   ├── observability.md          # Observability 전략 (Prometheus / Loki / Zipkin)
│   │   ├── grpc-guide.md             # gRPC 구현 가이드
│   │   ├── routinely-architecture.html  # 시스템 아키텍처 다이어그램 (HTML)
│   │   └── system-architecture.svg   # 시스템 아키텍처 다이어그램 (SVG)
│   ├── db/
│   │   ├── user-service.sql          # user-service DDL 참조
│   │   ├── routine-service.sql       # routine-service DDL 참조
│   │   ├── challenge-service.sql     # challenge-service DDL 참조
│   │   ├── chat-service.sql          # chat-service DDL 참조
│   │   └── notification-service.sql  # notification-service DDL 참조
│   ├── requirements/
│   │   ├── api-spec.md               # REST API 명세
│   │   ├── event-spec.md             # Kafka 이벤트 명세
│   │   └── grpc-spec.md              # gRPC 서비스 명세
│   └── decisions/                    # ADR (Architecture Decision Records)
│       ├── adr-0001-monorepo.md
│       ├── adr-0002-deployment-strategy-docker-compose.md
│       ├── adr-0003-nginx-gateway-structure.md
│       ├── adr-0004-service-boundary.md
│       ├── adr-0005-challenge-as-group.md
│       ├── adr-0006-gateway-jwt-validation.md
│       ├── adr-0007-communication-strategy.md
│       ├── adr-0008-kafka-adoption.md
│       ├── adr-0009-async-vs-pgmq.md
│       ├── adr-0010-chat-storage.md
│       ├── adr-0011-statistics-aggregation-strategy.md
│       ├── adr-0012-outbox-pattern.md
│       ├── adr-0013-idempotency-strategy.md
│       ├── adr-0014-kafka-consumer-resilience-strategy.md
│       ├── adr-0015-saga-strategy.md
│       ├── adr-0016-chat-broadcast-strategy.md
│       ├── adr-0017-notification-scheduling-strategy.md
│       ├── adr-0018-error-codes.md
│       ├── adr-0019-internal-service-security.md
│       ├── adr-0020-no-config-server.md
│       ├── adr-0021-realtime-transport-strategy.md
│       ├── adr-0022-observability-stack.md
│       ├── adr-0023-home-aggregation-strategy.md
│       ├── adr-0024-gateway-internal-communication.md
│       └── adr-0025-eureka-service-registry.md
│
├── libs/                             # 공통 라이브러리 (서비스 간 공유 코드)
│   ├── common-core/                  # 공통 유틸, 에러, 응답 포맷 (순수 도메인 — JPA/Web 의존 없음)
│   │   └── build.gradle
│   ├── common-jpa/                   # JPA 공통 설정 (BaseEntity, JpaAuditingConfig)
│   │   └── build.gradle
│   ├── common-web/                   # Web 필터, MDC, 공통 Exception Handler
│   │   └── build.gradle
│   ├── common-observability/         # 로깅 / 트레이싱 설정
│   │   └── build.gradle
│   └── proto/                        # gRPC Proto 정의 및 생성 스텁
│       └── build.gradle
│
├── services/                         # 마이크로서비스 모듈
│   ├── registry-service/             # Eureka Server — port 8761
│   │   ├── build.gradle
│   │   └── src/main/
│   │       ├── java/com/routinely/registry/
│   │       │   └── RegistryServiceApplication.java
│   │       └── resources/
│   │           └── application.yml
│   │
│   ├── gateway-service/              # Spring Cloud Gateway — port 8080
│   │   ├── build.gradle
│   │   └── src/main/
│   │       ├── java/com/routinely/gateway/
│   │       │   ├── filter/           # JWT 검증, MDC, Rate Limit 필터
│   │       │   └── GatewayApplication.java
│   │       └── resources/
│   │           └── application.yml
│   │
│   ├── user-service/                 # 회원가입 / 로그인 / 프로필 — port 8081
│   │   ├── build.gradle
│   │   └── src/main/
│   │       ├── java/com/routinely/user/
│   │       │   ├── domain/
│   │       │   ├── application/
│   │       │   ├── infrastructure/
│   │       │   └── presentation/
│   │       └── resources/
│   │           └── application.yml
│   │
│   ├── routine-service/              # 루틴 / 수행 기록 / 피드 / 통계 — port 8082
│   │   ├── build.gradle
│   │   └── src/main/
│   │       ├── java/com/routinely/routine/
│   │       │   ├── domain/
│   │       │   ├── application/
│   │       │   ├── infrastructure/
│   │       │   │   ├── persistence/
│   │       │   │   ├── kafka/        # Outbox Producer
│   │       │   │   └── grpc/         # gRPC Server (챌린지 검증 제공)
│   │       │   └── presentation/
│   │       └── resources/
│   │           └── application.yml
│   │
│   ├── challenge-service/            # 챌린지 / 참여 / 랭킹 — port 8083
│   │   ├── build.gradle
│   │   └── src/main/
│   │       ├── java/com/routinely/challenge/
│   │       │   ├── domain/
│   │       │   ├── application/
│   │       │   ├── infrastructure/
│   │       │   │   ├── persistence/
│   │       │   │   ├── kafka/
│   │       │   │   └── grpc/         # gRPC Server (멤버 검증 제공)
│   │       │   └── presentation/
│   │       └── resources/
│   │           └── application.yml
│   │
│   ├── chat-service/                 # WebSocket / STOMP 채팅 — port 8084
│   │   ├── build.gradle
│   │   └── src/main/
│   │       ├── java/com/routinely/chat/
│   │       │   ├── domain/
│   │       │   ├── application/
│   │       │   ├── infrastructure/
│   │       │   │   ├── persistence/
│   │       │   │   ├── kafka/        # 메시지 브로드캐스트 Consumer/Producer
│   │       │   │   └── websocket/    # STOMP 설정
│   │       │   └── presentation/
│   │       └── resources/
│   │           └── application.yml
│   │
│   └── notification-service/         # 예약 기반 알림 스케줄러 / 워커 — port 8085
│       ├── build.gradle
│       └── src/main/
│           ├── java/com/routinely/notification/
│           │   ├── domain/
│           │   ├── application/
│           │   ├── infrastructure/
│           │   │   ├── persistence/
│           │   │   ├── kafka/        # 이벤트 Consumer (Inbox)
│           │   │   ├── pgmq/         # PGMQ Worker (due 기반 발송)
│           │   │   └── grpc/         # gRPC Server (알림 예약 요청 수신)
│           │   └── presentation/
│           └── resources/
│               └── application.yml
│
├── infra/                            # 로컬 / 개발 환경 인프라
│   ├── docker-compose.yml            # PostgreSQL / Redis / Kafka (KRaft)
│   ├── docker-compose.observability.yml  # Zipkin / Prometheus / Loki / Alloy / Grafana
│   ├── .env.example                  # 환경 변수 템플릿 (커밋 대상; .env 는 .gitignore)
│   └── config/                       # 컨테이너 설정 파일
│       ├── postgres/
│       │   └── init.sql              # 서비스별 DB 5개 초기화
│       ├── prometheus/
│       │   └── prometheus.yml        # 스크랩 타겟 (host.docker.internal)
│       ├── alloy/
│       │   └── config.alloy          # 로그 수집 파이프라인 → Loki
│       └── grafana/
│           └── provisioning/
│               └── datasources/
│                   └── datasource.yml  # Prometheus, Loki 데이터소스 자동 등록
│
└── scripts/                          # 유틸리티 스크립트
    ├── local-up.sh                   # 로컬 환경 기동
    ├── local-down.sh                 # 로컬 환경 종료
    └── db-migrate.sh                 # DB 마이그레이션
```

---

## libs 의존성 설계

각 서비스가 필요에 따라 libs를 선택적으로 의존한다.

| 모듈 | 의존하는 libs | 비고 |
|---|---|---|
| registry-service | 없음 | 순수 Eureka Server |
| gateway-service | common-core, common-observability | JPA 없음 → common-jpa 미사용 |
| user-service | common-web, common-jpa, common-observability | common-web 이 common-core 포함 |
| routine-service | common-web, common-jpa, common-observability, proto | |
| challenge-service | common-web, common-jpa, common-observability, proto | |
| chat-service | common-web, common-jpa, common-observability, proto | |
| notification-service | common-web, common-jpa, common-observability | gRPC 미사용 → proto 불필요 |

---

## 서비스 내부 패키지 구조

모든 서비스는 아래 패키지 구조를 기준으로 구성한다.

```
com.routinely.{service}/
├── domain/                   # 핵심 도메인 (Entity, VO, Domain Service, Repository Interface)
├── application/              # Use Case / Service (비즈니스 로직 조합)
├── infrastructure/           # 외부 의존성 구현체
│   ├── persistence/          # JPA Repository 구현, QueryDSL
│   ├── kafka/                # Producer (Outbox), Consumer (Inbox + @RetryableTopic)
│   ├── grpc/                 # gRPC Server / Client Stub
│   └── pgmq/                 # PGMQ Worker (notification-service 전용)
├── presentation/             # 외부 진입점
│   ├── rest/                 # REST Controller, Request/Response DTO
│   └── grpc/                 # gRPC Service 구현 (proto 기반)
└── {Service}Application.java
```

---

## 채택 근거

### libs 분리 방식

| 모듈 | 역할 | 분리 이유 |
|---|---|---|
| `common-core` | 공통 유틸, 에러, 응답 포맷 | JPA/Web 의존 없이 모든 서비스가 사용 가능한 순수 도메인 모듈 |
| `common-jpa` | BaseEntity, JpaAuditingConfig | JPA가 필요 없는 서비스(gateway)의 클래스패스 오염 방지 |
| `common-web` | Web 필터, MDC, Exception Handler | Web 의존이 불필요한 서비스의 오염 방지 |
| `common-observability` | 로깅/트레이싱 설정 | 관찰 가능성 관련 설정을 중앙화 |
| `proto` | gRPC Proto 정의 + 생성 스텁 | 서비스 간 계약을 단일 위치에서 관리 |

### proto를 libs 하위에 배치한 이유

- 최상위 `proto/`로 두면 서비스와 동등한 위치가 되어 관리 경계가 모호해진다
- `libs/proto/`에 두면 "서비스 간 공유 라이브러리"임을 명확히 표현할 수 있다

---

## 기술 스택

| 항목 | 선택 |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.0.5 |
| Build Tool | Gradle (Groovy DSL) |
| Group | `com.routinely` |
| Packaging | JAR |
| Configuration | YAML |
