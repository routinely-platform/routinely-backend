# ADR-0045: 알림 예약 구현 — 테이블 폴링 + 발송 직전 판정

## Status

Accepted (2026-09-13)

**개정** — [ADR-0009](adr-0009-async-vs-pgmq.md) 구현 수단(PGMQ → 테이블 폴링. "내구성 있는 작업 큐를 쓴다"는 결론은 유지) ·
[ADR-0017](adr-0017-notification-scheduling-strategy.md) Decision 2 · Architecture Overview(PGMQ 흐름)

---

## 1. Context

ADR-0017은 알림을 **Due 기반 예약 + PGMQ + Next-One Chaining**으로 정했다. 구현 전에 두 가지가 비어 있었다.

### 1.1 PGMQ 설계에 결함 하나와 미충족 전제 하나가 있었다 (2026-08-12 검토)

**알림 시각을 바꾸면 옛 메시지가 남는다.** 사용자가 루틴 알림을 07:00 → 09:00으로 바꾸면 `notification_schedules`는
UPSERT되지만 **큐에 이미 들어간 07:00 메시지는 그대로다.** 워커의 `status == PENDING` 가드로는 막히지 않는다 —
스케줄은 실제로 여전히 PENDING이라 **다음 날 07:00에 조기 발송된다.** 막으려면 UPSERT마다 `pgmq.delete(옛 msg_id)`와
워커 가드 확장을 **두 겹으로** 넣어야 하는데, 패턴 문서 예제에는 삭제가 빠져 있었다.

**PGMQ 확장이 인프라에 없다.** `infra/docker-compose.yml`이 `postgres:17-alpine`이고 `CREATE EXTENSION pgmq` 흔적이 없다.
쓰려면 로컬·CI·운영 **세 곳의 이미지를 바꿔야 한다.**

### 1.2 "이 알림을 보내도 되나"를 판정할 주체가 없었다

`docs/product/policies.md` §8의 발송 규칙은 **그 사용자가 그 루틴을 이미 했는지**를 알아야 판정된다.

| 규칙 | 필요한 사실 |
|---|---|
| `DEADLINE` — 21:00, 그날 안 한 의무 루틴이 있을 때만 | 오늘 완료 여부 |
| 지정형 `ROUTINE_START` — 그날 이미 했으면 생략 (2026-09-13 추가) | 오늘 완료 여부 |
| 빈도형 `ROUTINE_START` — 이번 기간 목표를 채웠으면 생략 | 이번 기간 완료 수 |
| 빈도형 `DEADLINE` — 주/달 마지막 날 미달이면 | 이번 기간 완료 수 |

그 사실은 routine-service에만 있다. 게다가 `routine.notification.scheduled` 페이로드에는 `nextSendAt` 하나뿐이라
**발송 뒤 다음 1건을 계산할 재료도 없었다**(패턴 문서의 `scheduleNext`가 `TODO`).

---

## 2. Decision

1. **예약은 `notification_schedules` 테이블에 두고 폴링한다.** PGMQ를 쓰지 않는다
2. **다음 발송 시각은 notification-service가 계산한다** — `routine.notification.scheduled`가 **루틴 알림 일정의 스냅샷**을 싣는다
3. **보낼지는 발송 직전에 routine-service에 묻는다** — gRPC `CheckNotificationDue`(배치). 판정 규칙은 routine-service **한 곳**에만 있다
4. **Next-One은 유지한다** — 루틴·유형마다 다음 1건만 둔다

---

## 3. Rationale

### 3.1 폴링은 상태 원천이 한 곳이다

PGMQ는 **예약 표와 큐** 두 곳에 상태가 있어 둘을 맞추는 코드가 필요했고, 1.1의 결함이 거기서 났다.
폴링은 표 하나라 **알림 시각 변경이 `UPDATE` 한 줄**이다 — 옛 메시지가 남을 곳이 없다.

| 축 | PGMQ | 테이블 폴링 |
|---|---|---|
| 내구성 · 트랜잭션 · 동시성(`SKIP LOCKED`) | PostgreSQL | PostgreSQL — **무승부** |
| 상태 원천 | 표 + 큐 | **표 하나** |
| 알림 시각 변경 | UPSERT + 큐 메시지 삭제 | **UPDATE** |
| 재시도 백오프 | vt 조정 | **컬럼 하나**(`next_attempt_at`) |
| 인프라 | 확장 설치 · 이미지 3곳 교체 | **없음** |
| 테스트 | 확장이 깔린 DB 필요 | 기존 Testcontainers 그대로 |
| 선례 | 없음 | **Inbox 스케줄러**(routine · challenge) · ShedLock(ADR-0033) |

### 3.2 되돌리기 비용이 비대칭이다

폴링 → PGMQ는 **더하는** 변경이다. PGMQ → 폴링은 이미지 세 곳 롤백까지 **걷어내는** 변경이다.
지금 규모(최대 20명 그룹 · 알림 유형 3종)에서는 싼 쪽으로 시작하고, 신호가 오면 옮긴다(§6).

### 3.3 판정 규칙은 한 곳에만 둔다

주 경계(일요일 · `Asia/Seoul`)와 캡(`min(주별 완료, N)`)은 **통계(#60)와 같은 규칙**이다.
알림 쪽이 완료 상태를 따로 보관하면 같은 규칙이 두 서비스에 생기고, 홈의 `2/3`과 알림의 판단이 어긋나는 날이 온다.

---

## 4. 기각한 대안

| | 방식 | 기각 이유 |
|---|---|---|
| A | notification-service가 `routine.execution.completed` · `cancelled`를 소비해 **완료 상태를 복제** | 주 경계·캡을 두 서비스가 각자 구현한다. 백필·취소 역전에도 대응해야 한다 |
| B | **routine-service가 체인**을 맡아 완료·취소 때 다음 1건을 갈아 끼운다 | 알림을 **보낸 뒤** 다음 1건을 계산할 주체가 없다 — 알림 쪽이 계산하면 결국 일정을 알아야 하고(= 채택안), 루틴 쪽이 계산하면 "방금 보냈다"를 전달받는 왕복이 생긴다 |
| C | PGMQ 유지 + 재예약 결함 보강 | 1.1의 두 겹 방어와 이미지 교체를 감수할 이유가 지금 규모에서 없다 |

---

## 5. 트레이드오프

- **발송이 폴링 주기만큼 늦을 수 있다.** 주기를 수 초로 두면 "7시 정각"에 충분하다
- **notification → routine 동기 의존이 생긴다.** 21:00 마감 알림 시각에 호출이 몰린다 — **사용자 단위로 묶어** 한 번에 묻는다
- **판정 호출이 실패하면** 짧게 재시도하고, 그래도 실패하면 **보내지 않는다**(구현 노트 — #69에서 확정).
  판정이 필요한 알림은 전부 "이미 했을 수도 있는" 알림이라, 틀리면 다 한 사람에게 안 했다고 말하게 된다

---

## 6. PGMQ로 옮길 신호

- 알림 채널이 3개 이상으로 늘어 **워커 풀을 나눠야** 한다
- **즉시 실행 Job**이 예약 Job보다 많아진다
- due 폴링이 **초당 수백 건**을 넘는다

---

## 7. 구현 노트

### 7.1 스키마 (notification-service)

- `notification_schedules` — `user_id` · `routine_id`(DEADLINE은 NULL) · `type` · `next_send_at` · `status` · `attempt_count` · `next_attempt_at`
- 루틴 일정 스냅샷 — `title` · `schedule_type` · `days_of_week` · `target_count` · `preferred_time` · `preferred_days` · `started_at` · `ended_at` · `snapshot_at`
- 유일성 — `(user_id, routine_id, type)`. `DEADLINE`은 부분 UNIQUE `(user_id) WHERE type = 'DEADLINE'` — NULL이 서로 달라 보통 UNIQUE로는 안 막힌다
- 폴링 인덱스 — `(next_send_at) WHERE status = 'PENDING'` (정렬 컬럼을 키로 — 부분 인덱스 조건 컬럼을 키로 두지 않는다, #61 참고)

### 7.2 폴링 — ShedLock 단일 실행 (2026-09-13 확정)

**Inbox 스케줄러와 같이 ShedLock으로 한 대만 돈다**(ADR-0033). Outbox 폴러(`ChallengeOutboxPoller`)는
`FOR UPDATE SKIP LOCKED`로 여러 대가 나눠 가지는데, 그 선례를 따르지 않는 이유가 있다.

> **이 결정은 알림 발송 스케줄러에만 해당한다.** 기존 스케줄러는 그대로 둔다 —
> `ChallengeOutboxPoller`는 SKIP LOCKED, Inbox 스케줄러와 챌린지 상태 전이 스케줄러는 ShedLock.
> #61의 `RoutineOutboxPoller`도 Outbox 선례대로 SKIP LOCKED를 권고한다. 처리 도중 원격 호출이 끼느냐가 갈림길이다.

**처리 도중에 원격 호출이 낀다.** SKIP LOCKED는 트랜잭션 안에서 행을 잠근 채 처리하고 커밋하며 푼다. 여기서는 그 사이에
routine-service 판정(gRPC)과 SSE 발송이 들어가 **잠금과 DB 커넥션을 원격 호출 시간만큼 쥔다** —
"트랜잭션 안에서 gRPC를 호출하지 않는다"는 규칙에 걸린다(`service-interaction-map.md` §3).
규칙을 지키려면 **선점 방식**(`PROCESSING` + 만료 시각을 찍고 커밋 → 트랜잭션 밖에서 처리 → 반영 → 죽은 워커의 선점 회수)이
필요하고, "회수 직후 원래 워커가 살아나 둘 다 보내는" 경합까지 다뤄야 한다.

ShedLock은 한 대만 돌기 때문에 **잠금 없이 읽고, 트랜잭션 밖에서 호출하고, 행마다 짧게 UPDATE**하면 된다.

- `lockAtMostFor`는 **짧게**(권고 30초) — 잠금을 쥔 인스턴스가 죽으면 그동안 아무도 발송하지 않는다.
  기존 스케줄러의 5분을 그대로 쓰면 **알림이 최대 5분 늦는다**
- 한 번 실행의 처리량을 **`lockAtMostFor`보다 확실히 짧게 끊는다**(건수 · 경과 시간 상한) —
  실행이 잠금 시간을 넘기면 잠금이 풀려 **다른 인스턴스가 같은 예약을 동시에 보낸다**
- **발송 전에 조건부 UPDATE로 한 겹 더 막는다** — 다음 1건으로 먼저 넘기는 `UPDATE … WHERE id = ? AND status = 'PENDING' AND next_send_at = ?`가
  0행이면 보내지 않는다. 넘긴 직후 죽으면 그 회차는 빠진다(최대 1회 누락) — **중복 발송보다 낫다**(#69에서 확정).
  ShedLock은 중복 실행을 줄이는 최적화이고, 정합성은 DB가 보장한다(ADR-0033 §3과 같은 원칙)
- `next_send_at <= now()` 건을 묶어 **사용자 단위로 판정을 한 번에** 묻는다
- 수신 설정 켜기/끄기(#109)도 **발송 직전에** 읽는다

> **병목은 DB가 아니라 gRPC·SSE다.** 21:00에 1,000명이 몰려도 사용자 단위로 묶으면 호출 수십 번이다.
>
> **SKIP LOCKED 선점 방식으로 옮길 신호** — 한 번 실행한 분량을 다음 주기 전에 못 끝낸다 / 인스턴스 장애 시 수십 초 지연도 허용되지 않는다.
>
> SSE 연결은 인스턴스마다 붙어 있어, 여러 대를 쓰면 **잠금 방식과 무관하게** 인스턴스 간 전달(예: Redis pub/sub)이 필요하다.

### 7.3 이벤트 — `routine.notification.scheduled`

루틴의 **알림 일정이 바뀔 때마다** 최신 스냅샷을 발행한다 — 시작(개인 · 챌린지 #157) · 선호 시각/요일 수정 · 정의 수정 · 중단 · 챌린지 탈퇴 비활성(#158).

- `preferredTime`이 null이면 그 루틴의 `ROUTINE_START` 예약을 **지운다**
- 비활성·기간 종료면 그 루틴의 예약을 **지운다**
- 사용자에게 활성 루틴이 하나라도 있으면 `DEADLINE`(매일 21:00) 1건을 유지한다
- 늦게 온 옛 스냅샷은 `snapshot_at` 비교로 버린다

상세 페이로드는 `docs/requirements/event-spec.md` §2.

### 7.4 gRPC — `CheckNotificationDue` (notification → routine)

- 요청 — `userId`와 `[{ routineId | null, type, at }]`
- 응답 — 항목별 `shouldSend`
- 판정 규칙은 `policies.md` §8. 주 경계·캡은 #60과 **같은 함수**를 쓴다

---

## 8. Related ADRs

- **ADR-0009** 작업 큐 선택 — 결론 유지, 구현 수단만 개정
- **ADR-0017** 알림 스케줄링 — Due 기반 · Next-One · SSE 유지, PGMQ 흐름 대체
- ADR-0033 스케줄러 분산 락(ShedLock) · ADR-0013 멱등성 · ADR-0014 Kafka 소비자 복원력
- ADR-0039 반복 스케줄 · ADR-0041 무기한 루틴 · ADR-0043 달성률·캡
