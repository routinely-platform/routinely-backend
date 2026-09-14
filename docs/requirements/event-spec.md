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
| `routine.execution.completed` | RoutineService | ChallengeService | `userId` |
| `routine.execution.cancelled` 🆕 | RoutineService | ChallengeService | `userId` |
| `routine.notification.scheduled` | RoutineService | NotificationService | `userId` |
| ~~`challenge.created`~~ | ~~ChallengeService~~ | ~~RoutineService~~ | ⛔ 폐지 (ADR-0044) |
| `challenge.started` | ChallengeService | RoutineService, NotificationService, ChatService | `challengeId` |
| `challenge.ended` | ChallengeService | NotificationService, ChatService | `challengeId` |
| `challenge.member.joined` | ChallengeService | ChallengeService(랭킹) · 🔵 v2: RoutineService, ChatService (#118) | `challengeId` |
| `challenge.member.left` | ChallengeService | RoutineService, ChallengeService(랭킹), ChatService, NotificationService(방장 승계) | `challengeId` |
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
| Consumer Group | `challenge-service.ranking.routine.execution.completed` |

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

- **ChallengeService** (`challenge-service.ranking.routine.execution.completed`): `challenge_member_summary` UPSERT → Redis ZSET(`ranking:{challengeId}`) 동기화 (ADR-0028). 점수는 **누적 인정 횟수** — 페이로드 계약 변경은 #61(S33)

> **RoutineService는 이 토픽을 구독하지 않는다.** 달성률·스트릭은 저장하지 않고 조회할 때 계산한다(ADR-0043) — `routine_daily_summary` 갱신은 폐기됐다
> **NotificationService는 구독하지 않는다 (ADR-0045).** "이번 기간 목표를 채웠나 · 오늘 안 한 의무 루틴이 있나"는
> **발송 직전에 routine-service gRPC `CheckNotificationDue`로 묻는다** — 완료 상태를 알림 쪽에 복제하지 않는다.
> 전에 적혀 있던 "스트릭 달성 · 루틴 완료 후속 알림"은 알림 유형 3종에 없다(`policies.md` §8)

---

### 2. `routine.notification.scheduled`

루틴의 **알림 일정이 바뀔 때마다** 그 루틴의 최신 스냅샷을 발행한다. notification-service는 이 스냅샷으로
**다음 발송 시각을 스스로 계산**하고, **보낼지는 발송 직전에 routine-service에 묻는다**(ADR-0045).

| 항목 | 내용 |
|------|------|
| Publisher | RoutineService (Outbox) |
| Partition Key | `userId` |
| Consumer Group | `notification-service.routine.notification.scheduled` |

**발행 시점** — 루틴 시작(개인 · 챌린지 #157) · 선호 시각/요일 수정 · 정의 수정 · 중단 · 챌린지 탈퇴 비활성(#158)

**Payload**

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440001",
  "occurredAt": "2026-09-13T08:00:00Z",
  "userId": 1,
  "routineId": 42,
  "title": "아침 러닝",
  "active": true,
  "scheduleType": "WEEKLY_COUNT",
  "daysOfWeek": null,
  "targetCount": 3,
  "preferredTime": "07:00",
  "preferredDays": ["MON", "WED", "FRI"],
  "startedAt": "2026-09-01",
  "endedAt": null
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| `userId` | long | ✅ | 알림 수신 대상 |
| `routineId` | long | ✅ | **루틴 인스턴스 ID** — 템플릿 ID가 아니다(ADR-0040) |
| `title` | string | ✅ | 알림에 표시할 루틴 제목 |
| `active` | boolean | ✅ | `false`면 이 루틴의 예약을 지운다 |
| `scheduleType` | string | ✅ | `DAILY` · `SPECIFIC_DAYS` · `WEEKLY_COUNT` · `MONTHLY_COUNT` |
| `daysOfWeek` | string[] | ❌ | `SPECIFIC_DAYS`만 |
| `targetCount` | int | ❌ | 빈도형만 |
| `preferredTime` | string (HH:mm) | ❌ | **정각에 보낸다.** null이면 `ROUTINE_START`를 보내지 않는다 |
| `preferredDays` | string[] | ❌ | 빈도형 리마인더를 보낼 요일 — 없으면 보내지 않는다 |
| `startedAt` | string (yyyy-MM-dd) | ✅ | |
| `endedAt` | string (yyyy-MM-dd) | ❌ | null = 무기한(ADR-0041) |

**소비자 처리**

- **NotificationService**: 스냅샷을 저장하고 `notification_schedules`의 **다음 1건**을 UPSERT한다
  - `ROUTINE_START` — `preferredTime` 정각. 지정형은 수행 요일, 빈도형은 `preferredDays`에만.
    `preferredTime`이 null이거나 `active = false` · 기간 종료면 **지운다**
  - `DEADLINE` — 사용자에게 활성 루틴이 하나라도 있으면 매일 21:00 1건 유지
  - 늦게 온 옛 스냅샷(`occurredAt`이 저장된 것보다 이전)은 버린다
- **보낼지는 발송 직전에** routine-service gRPC `CheckNotificationDue`로 묻는다 — 이 이벤트에는 완료 상태가 없다

> 이전 설계(PGMQ enqueue · `nextSendAt` 하나 · `routineTemplateId` · 5분 전 06:55)는 **ADR-0045로 대체**됐다(2026-09-13).
- Next-One Chaining 원칙에 따라 이미 PENDING 레코드가 있으면 upsert로 갱신 (ADR-0017)

---

### 3. `challenge.created`

> ⛔ **폐지 (ADR-0044)** — 챌린지 루틴 정의를 challenge-service가 소유하면서 템플릿을 만들 이유가 사라졌다. 발행·소비 제거는 #167. 아래는 제거 전 기록이다

챌린지가 생성되었을 때 발행한다. routine-service가 소비해 챌린지 연결 루틴 템플릿(`routine_templates`, `challenge_id` UNIQUE)을 생성한다. (ADR-0034, 소비는 #133)

| 항목 | 내용 |
|------|------|
| Publisher | ChallengeService |
| Partition Key | `challengeId` |
| Consumer Group | `routine-service.challenge.created` |

**Payload**

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440007",
  "occurredAt": "2026-05-24T00:00:00Z",
  "challengeId": 1,
  "creatorUserId": 100,
  "categoryCode": "EXERCISE",
  "routineTitle": "아침 러닝 30분",
  "scheduleType": "WEEKLY_COUNT",
  "targetCount": 3,
  "startedAt": "2026-05-25",
  "endedAt": "2026-06-24"
}
```

| 필드 | 타입 | 필수 | routine_templates 매핑 | 설명 |
|------|------|:----:|------|------|
| `eventId` | string (UUID) | ✅ | — | Inbox 멱등성 키 |
| `occurredAt` | string (ISO 8601) | ✅ | — | 이벤트 발생 시각 |
| `challengeId` | long | ✅ | `challenge_id` (UNIQUE) | 소비자 멱등 키 |
| `creatorUserId` | long | ✅ | `user_id` | 템플릿 생성자 |
| `categoryCode` | string | ✅ | `category_code` | 카테고리 코드 |
| `routineTitle` | string | ✅ | `title` | 루틴명 |
| `scheduleType` | string | ✅ | `schedule_type` | `DAILY` \| `WEEKLY_COUNT` \| `MONTHLY_COUNT` (챌린지는 `SPECIFIC_DAYS` 불가 — ADR-0039) |
| `targetCount` | int | ❌ | `target_count` | `WEEKLY_COUNT`/`MONTHLY_COUNT`일 때만 존재(≥1) |
| `startedAt` | string (yyyy-MM-dd) | ✅ | — | 챌린지 시작일 |
| `endedAt` | string (yyyy-MM-dd) | ✅ | — | 챌린지 종료일 |

> routine-service가 추가 RPC 호출 없이 루틴 템플릿을 생성할 수 있도록 self-contained payload를 유지한다.
> 선호 수행 시각(`preferred_time`)은 이 payload에 포함하지 않는다. 챌린지 루틴 템플릿은 항상 `preferred_time = NULL`로 생성되며, 알림 시각은 멤버별 `routines` 인스턴스에서 개별 설정한다 (ADR-0035).

**소비자 처리**

- **RoutineService**: `routine_inbox`에 저장(`eventId` 멱등성) 후, 스케줄러가 챌린지 연결 루틴 템플릿(`routine_templates.challenge_id = challengeId`, UNIQUE)을 생성한다 (#133)

---

### 4. `challenge.started`

챌린지 시작일이 도래해 스케줄러가 WAITING → ACTIVE 상태 전이를 완료했을 때 발행한다. (ADR-0033)

| 항목 | 내용 |
|------|------|
| Publisher | ChallengeService (상태 전이 스케줄러) |
| Partition Key | `challengeId` |
| Consumer Group | `routine-service.challenge.started` / `notification-service.challenge.started` / `chat-service.challenge.started` |

**Payload**

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440005",
  "occurredAt": "2025-03-01T00:00:00Z",
  "challengeId": 5,
  "challengeName": "30일 러닝 챌린지",
  "startedAt": "2025-03-01",
  "endedAt": "2025-03-31"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| `challengeId` | long | ✅ | 챌린지 ID |
| `challengeName` | string | ✅ | 챌린지 이름 |
| `startedAt` | string (yyyy-MM-dd) | ✅ | 챌린지 시작일 |
| `endedAt` | string (yyyy-MM-dd) | ✅ | 챌린지 종료일 |

**소비자 처리**

- **RoutineService**: 페이로드의 멤버마다 `routines` 인스턴스를 만들고 **루틴 정의를 복사**한다(ADR-0032 · ADR-0040 · ADR-0044, #157). 실행 기록은 만들지 않는다 — 희소 저장(ADR-0038)
- **NotificationService**: 챌린지 멤버 전원에게 "챌린지가 시작되었습니다" 알림 발송
- **ChatService**: **채팅방을 만들고**(챌린지당 1개) 페이로드 멤버로 `chat_room_members`를 채운 뒤 SYSTEM 메시지 발행 ("챌린지가 시작되었습니다"). 방장(`OWNER`)은 페이로드의 **`leaderUserId`** 로 정한다 — 필드 추가는 #167 (ADR-0046)

---

### 5. `challenge.member.joined`

사용자가 챌린지에 참여했을 때 발행한다.

| 항목 | 내용 |
|------|------|
| Publisher | ChallengeService |
| Partition Key | `challengeId` |
| Consumer Group | `challenge-service.ranking.member.joined` · 🔵 v2: `routine-service.challenge.member.joined` / `chat-service.challenge.member.joined` |

**Payload**

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440002",
  "occurredAt": "2025-02-15T09:00:00Z",
  "challengeId": 5,
  "challengeName": "30일 러닝 챌린지",
  "userId": 1,
  "role": "MEMBER"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| `challengeId` | long | ✅ | 챌린지 ID |
| `challengeName` | string | ✅ | 챌린지 이름 (알림 메시지 활용) |
| `userId` | long | ✅ | 참여한 사용자 ID |
| `userNickname` | string | ❌ | 참여한 사용자 닉네임. MVP ChallengeService에는 UserService 조회 클라이언트가 없어 미포함 |
| `role` | string | ✅ | `LEADER` \| `MEMBER` |

**소비자 처리**

- **ChallengeService** (`challenge-service.ranking.member.joined`): 랭킹 행 초기화 (#48)
- 🔵 **v2 RoutineService**: `ACTIVE` 재참여 시 챌린지 루틴 인스턴스 복원 (#118)
- 🔵 **v2 ChatService**: `ACTIVE` 참여 시 `chat_room_members` 추가 + SYSTEM 메시지

> **MVP에서 RoutineService·ChatService는 구독하지 않는다.** 참여는 시작 전에만 가능해 루틴 인스턴스도 채팅방도 아직 없다 — 시작 시점 멤버는 `challenge.started` 페이로드로 한꺼번에 들어온다.
> **NotificationService도 구독하지 않는다.** 멤버 참여 알림은 없다(`policies.md` §8). 실행 기록 사전 생성은 폐기됐다(ADR-0038)

---

### 6. `challenge.member.left`

사용자가 챌린지를 탈퇴하거나 추방되었을 때 발행한다.

| 항목 | 내용 |
|------|------|
| Publisher | ChallengeService |
| Partition Key | `challengeId` |
| Consumer Group | `routine-service.challenge.member.left` / `challenge-service.ranking.member.left` / `chat-service.challenge.member.left` / `notification-service.challenge.member.left` |

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

- **RoutineService**: 그 멤버의 챌린지 루틴을 `is_active = false`로. 실행 기록은 남긴다 — 달력에 그대로 보인다 (#158, ADR-0038)
- **ChallengeService** (`challenge-service.ranking.member.left`): 랭킹 제외 — ZSET `ZREM`, summary 행은 유지 (#158)
- **ChatService**: `chat_room_members` 비활성화 + `left_at` · `newLeaderUserId`가 있으면 `OWNER` 승계 · SYSTEM 메시지. **방이 아직 없으면(`WAITING`) 할 일이 없다**
- **NotificationService**: `newLeaderUserId`가 있으면 **새 방장에게** `CHALLENGE_EVENT`(방장 승계) (`policies.md` §8).
  알림 제목에 챌린지 이름이 필요하다 — notification-service는 챌린지 정보를 갖지 않으므로 **`challengeName`을 페이로드에 싣는다**(`newLeaderUserId`와 함께 #159)

---

### 7. `challenge.ended`

챌린지 종료일이 지나 스케줄러가 ACTIVE → ENDED 상태 전이를 완료했을 때 발행한다. (ADR-0033)

> **MVP에서 발행한다 (2026-09-13 정정).** 전에는 "구독자가 v2에서 구현될 때 활성화"라 적었는데, MVP에 구독자가 둘 있다 —
> 종료 알림(`policies.md` §8)과 채팅 종료 SYSTEM · 발송 차단(#162).
> **지금 코드는 `ACTIVE → ENDED` 전이만 하고 발행하지 않는다**(`ChallengeStatusTransitionScheduler` · `ChallengeService`의 `end()` 두 곳) — 발행은 신규 challenge-service 이슈에서 붙인다

| 항목 | 내용 |
|------|------|
| Publisher | ChallengeService (상태 전이 스케줄러) |
| Partition Key | `challengeId` |
| Consumer Group | `notification-service.challenge.ended` / `chat-service.challenge.ended` |

**Payload**

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440006",
  "occurredAt": "2025-04-01T00:00:00Z",
  "challengeId": 5,
  "challengeName": "30일 러닝 챌린지",
  "endedAt": "2025-03-31",
  "members": [ { "userId": 1 }, { "userId": 2 } ]
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|:----:|------|
| `challengeId` | long | ✅ | 챌린지 ID |
| `challengeName` | string | ✅ | 챌린지 이름 |
| `endedAt` | string (yyyy-MM-dd) | ✅ | 챌린지 종료일 |
| `members` | object[] | ✅ | **종료 시점의 활성 멤버** `[{ userId }]` — notification-service는 멤버 목록을 갖지 않는다 (2026-09-13 추가) |

**소비자 처리**

- **NotificationService**: 페이로드 `members` 전원에게 `CHALLENGE_EVENT` 종료 알림
- **ChatService**: SYSTEM 메시지 발행 ("챌린지가 종료되었습니다") · 이후 **발송 차단, 조회는 허용** (#162)

> **RoutineService는 구독하지 않는다.** 루틴 기간 = 챌린지 기간이라 종료 다음 날부터 대상이 아니게 된다(ADR-0038) — 마감 처리할 행이 없다(희소 저장). 최종 순위는 랭킹 조회가 곧 요약이다(`policies.md` §4)

---

### 8. `chat.message.created`

채팅 메시지가 PostgreSQL에 저장되었을 때 발행한다.
ChatService 멀티 인스턴스 간 WebSocket 브로드캐스트 용도. (ADR-0016)

| 항목 | 내용 |
|------|------|
| Publisher | ChatService (메시지를 수신한 인스턴스) |
| Partition Key | `roomId` — 방 단위 메시지 순서 보장 |
| Consumer Group | **인스턴스마다 다른 그룹** — `chat-service.broadcast.{instanceId}` (ADR-0016 보완) |

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
| `roomId` | long | ✅ | 채팅방 ID (내부 식별자 · 파티션 키) |
| `challengeId` | long | ✅ | 구독 주소 `/sub/chat/challenges/{challengeId}` — 받는 인스턴스가 방 번호로 챌린지를 다시 찾지 않게 (ADR-0046, 2026-09-13 추가) |
| `messageId` | long | ✅ | 저장된 메시지 ID — 클라이언트 중복 제거 키 |
| `senderId` | long | ❌ | 발신자 사용자 ID — **SYSTEM이면 null** (`ck_cm_sender_id`) |
| `senderNickname` | string | ❌ | 발신자 닉네임 — SYSTEM이면 null. user-service `GetUsers`(#153)로 얻는다 |
| `content` | string | ✅ | 메시지 내용 |
| `messageType` | string | ✅ | `TEXT` \| `SYSTEM` (`IMAGE`는 v2) |
| `systemType` | string | ❌ | SYSTEM일 때 — `STARTED` \| `ENDED` \| `MEMBER_LEFT` \| `LEADER_CHANGED` (2026-09-13 추가) |
| `targetUserId` | long | ❌ | `MEMBER_LEFT`면 나간 사람 — **받은 인스턴스가 그 사용자의 구독을 끊는다**(ADR-0046 §6) · `LEADER_CHANGED`면 새 방장 |
| `sentAt` | string (ISO 8601) | ✅ | 메시지 전송 시각 |

**소비자 처리**

- **ChatService (전 인스턴스)**: 자신의 WebSocket 세션 중 해당 `roomId` 구독자에게 브로드캐스트
- 해당 방에 연결된 세션이 없는 인스턴스는 consume 후 아무 동작도 하지 않는다

> **그룹을 인스턴스마다 따로 둔다 (2026-09-13 정정).** 전에는 "전 인스턴스 동일 그룹"이라 적었다. 그런데 같은 그룹이면
> 파티션 하나를 **한 인스턴스만** 받는다 — 다른 인스턴스에 붙은 사람은 메시지를 못 받아 **브로드캐스트가 되지 않는다.**
>
> - 그룹이 인스턴스마다 다르면 **모든 인스턴스가 모든 메시지를 받는다.** 방 단위 순서는 partition key `roomId`로 인스턴스마다 지켜진다
> - **Inbox를 쓰지 않는다** — `chat_inbox`는 인스턴스들이 함께 쓰는 표라, 먼저 받은 인스턴스가 행을 넣으면 나머지는 중복으로 보고 건너뛴다.
>   브로드캐스트는 DB에 쓰지 않아 두 번 전달돼도 해롭지 않다 — 클라이언트가 `messageId`로 거른다
> - 새로 뜬 인스턴스는 **최신 위치부터** 읽는다(`auto.offset.reset=latest`). 연결이 끊긴 사이 놓친 메시지는 클라이언트가 재연결 뒤 REST로 채운다
> - `@RetryableTopic`·DLT를 붙이지 않는다 — 인스턴스마다 재시도 토픽이 생기고, 늦게 도착한 브로드캐스트는 쓸모가 없다

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
