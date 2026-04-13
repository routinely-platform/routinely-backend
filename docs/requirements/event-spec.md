# 이벤트 명세 (Kafka Event Specification)

> 서비스 간 비동기 이벤트는 모두 Kafka를 통해 전달된다.
> 모든 이벤트는 Outbox 패턴을 통해 발행되어 DB 커밋과 정합성을 보장한다. (ADR-0012)

---

## 기본 원칙

- **이벤트는 과거형**으로 명명한다 — "이 일이 발생했다"는 사실만 전달
- **이벤트에 명령을 담지 않는다** — 소비자가 무엇을 할지는 소비자가 결정
- **Outbox 경유** — `kafkaTemplate.send()`를 직접 호출하지 않고 outbox 테이블을 거친다
- **멱등성 보장** — 소비자는 `eventId` + inbox 테이블로 중복 처리를 방지한다 (ADR-0013)

---

## 토픽 명명 규칙

```
{도메인}.{집합체}.{과거형 동사}
```

예: `routine.execution.completed`, `challenge.member.joined`

---

## 공통 메시지 필드

모든 이벤트 페이로드에 포함되는 필드:

| 필드 | 타입 | 설명 |
|------|------|------|
| `eventId` | string (UUID) | 이벤트 고유 식별자 — 멱등성 키 |
| `occurredAt` | string (ISO 8601) | 이벤트 발생 시각 |

---

## 토픽 목록 요약

| 토픽 | Publisher | Subscriber(s) | Partition Key |
|------|-----------|---------------|---------------|
| `routine.execution.completed` | RoutineService | NotificationService | `userId` |
| `routine.notification.scheduled` | RoutineService | NotificationService | `userId` |
| `challenge.member.joined` | ChallengeService | ChatService, NotificationService | `challengeId` |
| `challenge.member.left` | ChallengeService | ChatService | `challengeId` |
| `chat.message.created` | ChatService | ChatService (전 인스턴스) | `roomId` |

---

## 토픽별 명세

---

### 1. `routine.execution.completed`

루틴 실행이 COMPLETED 처리되었을 때 발행한다.

| 항목 | 내용 |
|------|------|
| Publisher | RoutineService |
| Partition Key | `userId` |
| Consumer Group | `notification-service.routine.execution.completed` |

**Payload**

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "occurredAt": "2025-02-15T07:00:00Z",
  "userId": 1,
  "routineTemplateId": 10,
  "executionId": 100,
  "execDate": "2025-02-15",
  "challengeId": 5
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| `userId` | long | ✅ | 실행한 사용자 ID |
| `routineTemplateId` | long | ✅ | 루틴 템플릿 ID |
| `executionId` | long | ✅ | 루틴 실행 레코드 ID |
| `execDate` | string (yyyy-MM-dd) | ✅ | 실행 날짜 |
| `challengeId` | long | ❌ | 챌린지 루틴인 경우만 포함. null = 개인 루틴 |

**소비자 처리**

- **NotificationService**: 스트릭 달성, 루틴 완료 등 후속 알림 판단 및 발송

---

### 2. `routine.notification.scheduled`

루틴이 생성되거나 수정되어 다음 알림 예약이 필요할 때 발행한다.

| 항목 | 내용 |
|------|------|
| Publisher | RoutineService |
| Partition Key | `userId` |
| Consumer Group | `notification-service.routine.notification.scheduled` |

**Payload**

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440001",
  "occurredAt": "2025-02-15T08:00:00Z",
  "userId": 1,
  "routineTemplateId": 10,
  "routineName": "아침 러닝",
  "nextSendAt": "2025-02-16T06:55:00Z",
  "notificationType": "ROUTINE_START"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| `userId` | long | ✅ | 알림 수신 대상 사용자 ID |
| `routineTemplateId` | long | ✅ | 루틴 템플릿 ID |
| `routineName` | string | ✅ | 알림에 표시될 루틴 이름 |
| `nextSendAt` | string (ISO 8601) | ✅ | 다음 알림 발송 예정 시각 |
| `notificationType` | string | ✅ | `ROUTINE_START` |

**소비자 처리**

- **NotificationService**: `NOTIFICATION_SCHEDULES` upsert(PENDING) → PGMQ enqueue(vt=nextSendAt)
- Next-One Chaining 원칙에 따라 이미 PENDING 레코드가 있으면 upsert로 갱신 (ADR-0017)

---

### 3. `challenge.member.joined`

사용자가 챌린지에 참여했을 때 발행한다.

| 항목 | 내용 |
|------|------|
| Publisher | ChallengeService |
| Partition Key | `challengeId` |
| Consumer Group | `chat-service.challenge.member.joined` / `notification-service.challenge.member.joined` |

**Payload**

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440002",
  "occurredAt": "2025-02-15T09:00:00Z",
  "challengeId": 5,
  "challengeName": "30일 러닝 챌린지",
  "userId": 1,
  "userNickname": "김루틴",
  "role": "MEMBER"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| `challengeId` | long | ✅ | 챌린지 ID |
| `challengeName` | string | ✅ | 챌린지 이름 (알림 메시지 활용) |
| `userId` | long | ✅ | 참여한 사용자 ID |
| `userNickname` | string | ✅ | 참여한 사용자 닉네임 |
| `role` | string | ✅ | `LEADER` \| `MEMBER` |

**소비자 처리**

- **ChatService**: 챌린지 채팅방 멤버 캐시 갱신
- **NotificationService**: 기존 챌린지 멤버 전원에게 CHALLENGE_EVENT 알림 발송

---

### 4. `challenge.member.left`

사용자가 챌린지를 탈퇴하거나 추방되었을 때 발행한다.

| 항목 | 내용 |
|------|------|
| Publisher | ChallengeService |
| Partition Key | `challengeId` |
| Consumer Group | `chat-service.challenge.member.left` |

**Payload**

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440003",
  "occurredAt": "2025-02-15T10:00:00Z",
  "challengeId": 5,
  "userId": 1,
  "reason": "LEFT"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| `challengeId` | long | ✅ | 챌린지 ID |
| `userId` | long | ✅ | 탈퇴/추방된 사용자 ID |
| `reason` | string | ✅ | `LEFT` \| `EXPELLED` |

**소비자 처리**

- **ChatService**: 채팅방 멤버 캐시 무효화

---

### 5. `chat.message.created`

채팅 메시지가 PostgreSQL에 저장되었을 때 발행한다.
ChatService 멀티 인스턴스 간 WebSocket 브로드캐스트 용도. (ADR-0016)

| 항목 | 내용 |
|------|------|
| Publisher | ChatService (메시지를 수신한 인스턴스) |
| Partition Key | `roomId` — 방 단위 메시지 순서 보장 |
| Consumer Group | `chat-service.chat.message.created` (전 인스턴스 동일 그룹) |

**Payload**

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440004",
  "occurredAt": "2025-02-15T10:05:00Z",
  "roomId": 1,
  "messageId": 200,
  "senderId": 1,
  "senderNickname": "김루틴",
  "content": "오늘도 달렸습니다!",
  "messageType": "TEXT",
  "sentAt": "2025-02-15T10:05:00Z"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| `roomId` | long | ✅ | 채팅방 ID |
| `messageId` | long | ✅ | 저장된 메시지 ID |
| `senderId` | long | ✅ | 발신자 사용자 ID |
| `senderNickname` | string | ✅ | 발신자 닉네임 |
| `content` | string | ✅ | 메시지 내용 |
| `messageType` | string | ✅ | `TEXT` \| `IMAGE` \| `SYSTEM` |
| `sentAt` | string (ISO 8601) | ✅ | 메시지 전송 시각 |

**소비자 처리**

- **ChatService (전 인스턴스)**: 자신의 WebSocket 세션 중 해당 `roomId` 구독자에게 브로드캐스트
- 해당 방에 연결된 세션이 없는 인스턴스는 consume 후 아무 동작도 하지 않는다

> `roomId`를 partition key로 사용하므로 하나의 채팅방 메시지는 항상 동일 파티션에 배치된다.
> 동일 consumer group 내에서 하나의 파티션은 한 인스턴스만 처리하므로 방 단위 순서가 보장된다.

---

## Outbox 발행 흐름

```
@Transactional
├── 도메인 데이터 저장
└── outbox 테이블 INSERT (status=PENDING, topic=..., payload=...)

[Outbox Worker — 별도 스케줄러]
└── PENDING 레코드 polling
    └── Kafka publish 성공
        └── status=PUBLISHED 업데이트
```

이벤트 발행 실패 시 outbox 레코드는 PENDING 상태를 유지하여 다음 polling 주기에 재시도된다.
