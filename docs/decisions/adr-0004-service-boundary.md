# ADR-0004: 서비스 분리 전략 (Service Boundary)

## Status
Accepted

---

## Context

Routinely는 반복 루틴을 기반으로 한 개인 기록 + 그룹 챌린지 플랫폼이다.

핵심 도메인은 다음과 같다:

- 사용자(User)
- 챌린지(Challenge)
- 루틴(Routine)
- 채팅(Chat)
- 알림(Notification)
- 통계/리포트(Report)

마이크로서비스 아키텍처(MSA)를 채택하면서,
각 도메인을 어떻게 분리할 것인지에 대한 명확한 경계 정의가 필요했다.

특히 다음과 같은 고민이 있었다:

- Challenge와 Routine을 하나의 서비스로 둘 것인가?
- Chat을 별도 서비스로 분리할 것인가?
- Report를 별도 서비스로 둘 것인가?
- Notification을 내부 모듈로 둘 것인가, 독립 서비스로 둘 것인가?

> **통계/리포트 관련 결정**: ReportService는 MVP 단계에서 도입하지 않는다.
> 통계 기능은 RoutineService에서 직접 제공한다. 자세한 근거는 `adr-0011` 참고.

---

## Decision

Routinely는 다음과 같이 서비스를 분리한다:

- UserService
- ChallengeService
- RoutineService
- ChatService
- NotificationService
- Gateway (Edge)
- Discovery (Eureka)

> ReportService는 MVP 단계에서 도입하지 않는다.
> 통계 기능(달성률, 스트릭, 그룹 랭킹)은 RoutineService 내에서 직접 제공한다.

각 서비스는 독립적인 데이터베이스를 소유한다 (Database per Service).

---

## Service Boundary Definition

### 1️⃣ UserService

**책임**
- 사용자 정보 관리
- 인증/인가 기반 사용자 식별
- 프로필 정보

**소유 데이터**
- USERS 테이블

---

### 2️⃣ ChallengeService

**책임**
- 챌린지 생성/수정/종료
- 멤버 관리
- 공개/비공개 정책
- 초대 코드 관리

**소유 데이터**
- CHALLENGES
- CHALLENGE_MEMBERS

Challenge는 그룹 역할을 겸한다 (MVP 단순화).

---

### 3️⃣ RoutineService

**책임**
- 루틴 템플릿 관리
- 루틴 실행 기록
- 피드 생성
- 루틴 완료 처리
- 통계 조회 (달성률, 스트릭, 그룹 평균, 그룹 내 랭킹)

**소유 데이터**
- ROUTINE_TEMPLATES
- ROUTINE_EXECUTIONS
- FEED_ITEMS
- FEED_REACTIONS

Routine은 Challenge를 참조할 수 있으나,
Challenge의 내부 상태를 직접 수정하지 않는다.

멤버 검증은 gRPC를 통해 ChallengeService에 위임한다.

통계 데이터의 단일 출처는 ROUTINE_EXECUTIONS이므로,
별도 서비스 없이 RoutineService가 직접 집계 쿼리를 수행한다.

---

### 4️⃣ ChatService

**책임**
- 그룹별 채팅방 관리
- 실시간 메시지 처리
- 메시지 저장

**소유 데이터**
- PostgreSQL: ChatRoom 메타, ChatMessage 본문

Chat은 Challenge에 종속되지만,
Challenge DB에 직접 접근하지 않는다.

---

### 5️⃣ NotificationService

**책임**
- 루틴 시작 알림
- 하루 마감 리마인드 알림
- 챌린지 이벤트 알림
- 예약 기반(sendAt) 처리

**소유 데이터**
- NOTIFICATION_SCHEDULES
- NOTIFICATION_DELIVERY_LOGS

서비스 내부 비동기 처리는 PGMQ 기반으로 수행한다.

---

---

## Rationale

1. **도메인 책임 분리**
    - 각 서비스는 하나의 명확한 책임을 가진다.
    - 변경 영향 범위를 최소화한다.

2. **확장성 고려**
    - 채팅은 트래픽 패턴이 다르므로 별도 확장 가능해야 한다.
    - 통계 집계는 별도 스케일 전략이 필요하다.

3. **비즈니스 관심사의 분리**
    - 루틴과 챌린지는 밀접하지만 책임이 다르다.
    - 알림은 크로스 컷팅 관심사이므로 독립 서비스로 둔다.

4. **데이터 소유권 명확화**
    - 각 서비스는 자신의 DB만 직접 접근한다.
    - 다른 서비스 DB에 직접 접근하지 않는다.

5. **MSA 원칙 준수**
    - Database per Service
    - 느슨한 결합
    - 서비스 간 통신은 gRPC 또는 Kafka

---

## Communication Between Services

- 동기 요청 → gRPC
- 도메인 이벤트 → Kafka
- 내부 비동기 작업 → PGMQ

직접적인 DB 공유는 허용하지 않는다.

---

## Consequences

### Positive

- 도메인 응집도 향상
- 독립 배포 가능
- 서비스 단위 확장 가능
- 장애 격리 가능

### Negative

- 서비스 간 통신 증가
- 분산 트랜잭션 부재 (Eventual Consistency 필요)
- 운영 복잡도 증가

---

## Architectural Principle

서비스는 "기능 단위"가 아니라  
"도메인 책임 단위"로 분리한다.

Routinely는 명확한 경계를 기반으로  
느슨하게 결합된 이벤트 기반 시스템을 지향한다.