# ADR-0015: Saga 패턴 전략 (Orchestration vs Choreography)

아무런 이유 없이 그냥 orchestration 방식 사용하는 건 포폴에 오히려 부작용!!
현재 routinely 프로젝트 구조에서 orchestration 방식은 필수가 아님!!
예를 들어 결제/리워드 방식이 필요하다면 이거는 필수겠지만 mvp에 없음

```
Routinely는 기본적으로 Choreography 기반 이벤트 구조를 사용하지만,
챌린지 시작/종료와 같이 다단계 상태 전이가 필요한 비즈니스 흐름에는
Orchestration 기반 Saga 패턴을 적용하여 트랜잭션 일관성과 보상 로직을 명확히 관리했습니다.
```
위와 같이 작성하면 이는 분명히 강점이 될 수 있음. orchestration 방식을 사용할 명분이나 이유를 찾아야 함!!

## Status
Accepted

---

## Context

Routinely는 마이크로서비스 아키텍처(MSA)를 기반으로 구성된다.

서비스 간에는 다음과 같은 분산 흐름이 존재한다:

- 루틴 완료 → 통계 집계 → 알림 스케줄 생성
- 챌린지 멤버 참여 → 채팅 권한 반영 → 통계 반영
- 챌린지 종료 → 알림 발송 → 통계 마감 처리

이러한 흐름은 단일 트랜잭션으로 묶을 수 없다.

따라서 분산 트랜잭션을 관리하기 위한 Saga 패턴 적용 여부와
그 방식(Orchestration vs Choreography)을 결정해야 했다.

---

## Options Considered

### Option 1: Orchestration 기반 Saga

중앙 Orchestrator 서비스가 존재하며:

- 각 서비스 호출 순서를 제어
- 실패 시 보상 트랜잭션 호출
- 전체 흐름을 명시적으로 관리

#### 장점

- 흐름이 명확함
- 제어 로직이 중앙 집중화
- 복잡한 트랜잭션 시나리오에 적합

#### 단점

- 중앙 서비스에 결합도 증가
- 단일 장애 지점(SPOF) 가능성
- 복잡성 증가
- 개인 프로젝트 규모에 과도함

---

### Option 2: Choreography 기반 Saga

각 서비스는 이벤트를 발행하고,
관심 있는 다른 서비스가 이를 구독하여 반응한다.

중앙 제어자는 없다.

#### 장점

- 느슨한 결합
- 확장성 우수
- 단순한 흐름에 적합
- Kafka 기반 구조와 자연스럽게 결합

#### 단점

- 흐름 가시성 감소
- 이벤트 흐름이 분산됨
- 복잡한 보상 트랜잭션 구현 어려움

---

## Decision

Routinely는 **Choreography 기반 Saga 패턴을 채택한다.**

즉,

- 서비스는 이벤트를 발행한다.
- 다른 서비스는 해당 이벤트를 구독하고 반응한다.
- 중앙 Orchestrator는 두지 않는다.

---

## Rationale

### 1️⃣ 현재 도메인 복잡도

Routinely의 분산 흐름은 다음과 같이 비교적 단순하다:

- 이벤트 발생 → 후속 처리

복잡한 다단계 보상 트랜잭션이 필요한 시나리오는 현재 없다.

---

### 2️⃣ Kafka 중심 이벤트 구조

이미 Kafka를 도입하여:

- routine.execution.done
- challenge.member.joined
- chat.message.created

이벤트 중심 구조를 채택하였다.

Choreography는 이 구조와 자연스럽게 맞는다.

---

### 3️⃣ 개인 프로젝트 규모

- 중앙 Orchestrator 도입은 복잡도 증가
- 운영 부담 증가
- 구현 비용 대비 이점이 낮음

---

### 4️⃣ 느슨한 결합 유지

Choreography는:

- 서비스 간 직접 의존 감소
- 확장 시 영향 최소화
- 새로운 소비자 추가 용이

---

## Example Flow

### 루틴 완료 시

1. RoutineService
    - execution 저장
    - outbox 기록
    - Kafka publish: routine.execution.done

2. ReportService
    - 이벤트 consume
    - 통계 집계

3. NotificationService
    - 이벤트 consume
    - 알림 스케줄 생성

중앙 제어자는 없다.

---

## Compensation Strategy

현재 Routinely는:

- 보상 트랜잭션이 필요한 복잡한 금전/재고 도메인이 없음
- 이벤트 기반 재처리 및 멱등성 전략으로 안정성 확보

향후 복잡한 시나리오가 등장할 경우,
Orchestration 도입을 재검토할 수 있다.

---

## Consequences

### Positive

- 서비스 간 느슨한 결합
- 확장성 확보
- Kafka 구조와 자연스러운 통합
- 구현 복잡도 감소

### Negative

- 이벤트 흐름 가시성 감소
- 디버깅 난이도 증가
- 복잡한 보상 트랜잭션 구현 어려움

---

## Architectural Principle

Routinely는  
"Event-Driven, Loosely Coupled Architecture"를 지향한다.

현재 도메인 복잡도와 프로젝트 규모를 고려하여  
Choreography 기반 Saga 패턴을 채택한다.