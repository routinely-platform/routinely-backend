# ADR-0008: Kafka 도입 결정 (RabbitMQ와 비교 포함)

## Status
Accepted

---

## Context

Routinely는 마이크로서비스 아키텍처(MSA)를 기반으로 구성된다.

서비스 간 다음과 같은 비동기 이벤트 전달이 필요하다:

- 루틴 완료 → 통계 집계 트리거
- 루틴 완료 → 알림 스케줄 생성
- 챌린지 멤버 변경 → 통계/캐시 무효화
- 채팅 메시지 생성 → 멀티 인스턴스 브로드캐스트
- (향후) 활동 분석, 추천 기능 확장

비동기 이벤트 브로커로 다음 선택지를 고려했다:

1. Kafka
2. RabbitMQ

---

## Decision

Routinely는 서비스 간 도메인 이벤트 브로커로 **Kafka를 채택한다.**

RabbitMQ는 채택하지 않는다.

---

## Options Considered

### Option 1: RabbitMQ

#### 특징

- 전통적인 메시지 브로커
- 큐 기반 메시징
- 라우팅(exchange) 기능 강력
- 작업 큐(Work Queue)에 적합

#### 장점

- 비교적 설정이 단순함
- TTL, DLQ 설정이 직관적
- 요청-응답 패턴 구현 용이
- STOMP 브로커 릴레이에 적합

#### 단점

- 이벤트 스트림 성격이 약함
- 메시지 리플레이 기능 제한적
- 다중 구독자 확장 시 구조 복잡
- 대규모 스트리밍에는 상대적으로 부적합

---

### Option 2: Kafka

#### 특징

- 분산 이벤트 스트리밍 플랫폼
- 로그 기반 저장 구조
- 파티션 기반 확장
- 리플레이 가능

#### 장점

- 다중 구독자(fan-out)에 매우 적합
- 이벤트 리플레이 가능
- 높은 처리량
- 파티션 키 기반 순서 보장
- 채팅 멀티 인스턴스 확장에 적합
- 향후 분석/추천 기능 확장 용이

#### 단점

- 운영 복잡도 증가
- 초기 설정 난이도 존재
- 단순 작업 큐에는 과할 수 있음

---

## Rationale

### 1️⃣ Routinely는 "이벤트 중심 구조"이다

Routinely의 핵심 흐름은 이벤트 기반이다:

- `routine.execution.done`
- `challenge.member.joined`
- `chat.message.created`

이러한 이벤트는 하나의 서비스가 아니라
여러 서비스에서 구독할 수 있다.

Kafka는 이러한 fan-out 구조에 적합하다.

---

### 2️⃣ 채팅 확장성 고려

ChatService는 멀티 인스턴스로 확장될 수 있다.

- 인스턴스 A에서 메시지 수신
- Kafka publish
- 인스턴스 B, C가 consume
- 각 인스턴스가 로컬 WebSocket 세션에 브로드캐스트

Kafka는 roomId를 파티션 키로 사용하여
방 단위 메시지 순서를 보장할 수 있다.

---

### 3️⃣ 리플레이 가능성

통계 로직 변경 시,
과거 이벤트를 다시 소비하여 재집계할 수 있다.

RabbitMQ는 기본적으로 로그 저장 기반이 아니므로
이벤트 재처리에 한계가 있다.

---

### 4️⃣ PGMQ와 역할 분리

Routinely는 다음과 같이 역할을 분리한다:

- Kafka → 서비스 간 도메인 이벤트
- PGMQ → 서비스 내부 비동기 작업

RabbitMQ는 작업 큐 성격에 강점이 있으나,
해당 영역은 PGMQ로 이미 해결한다.

따라서 이벤트 브로커는 Kafka로 통일하는 것이 일관성 있다.

---

## Usage in Routinely

Kafka는 다음 이벤트에 사용된다:

- `routine.execution.done`
- `challenge.member.joined`
- `challenge.member.left`
- `chat.message.created`

Kafka는 "이 일이 발생했다"는 사실을 전달하는 용도로 사용하며,
서비스 내부 작업 처리는 담당하지 않는다.

---

## Consequences

### Positive

- 서비스 간 느슨한 결합
- 다중 구독자 확장 용이
- 이벤트 재처리 가능
- 채팅 수평 확장 대응 가능
- 향후 분석/추천 기능 확장성 확보

### Negative

- Kafka 운영 부담 증가
- 인프라 복잡도 상승
- 모니터링 필요성 증가

---

## Architectural Principle

- Command는 gRPC
- Event는 Kafka
- Internal Job은 PGMQ

Kafka는 Routinely의
**서비스 간 도메인 이벤트 전파의 표준 브로커로 사용한다.**