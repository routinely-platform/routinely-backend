# ADR-0017: 알림 처리 전략 — PGMQ 기반 예약 실행 + Next-One Chaining

## Status

Accepted

---

## Context

Routinely는 다음과 같은 알림 기능을 제공한다:

- 루틴 시작 5분 전 알림
- 매일 특정 시각의 루틴 공지 (Daily Digest)
- 챌린지 이벤트 알림 (시작/종료 등)

알림 처리 전략을 설계하면서 아래 세 가지 문제를 해결해야 했다:

1. **어떻게 발송 대상을 찾을 것인가** — 전수 스캔(Pull) vs 사전 예약(Due 기반)
2. **내부 비동기 큐를 어떤 기술로 구현할 것인가** — Kafka vs PGMQ vs 직접 구현
3. **실시간 알림을 어떻게 전달할 것인가** — FCM(Push) vs SSE

---

## Decision

Routinely는 다음 전략을 채택한다:

1. **Due 기반 예약 처리** — 전수 스캔 대신 미리 예약된 알림만 처리한다.
2. **PGMQ** — 별도 인프라 없이 PostgreSQL 기반 큐를 사용한다.
3. **Next-One Chaining** — 반복 알림은 항상 "가장 가까운 다음 1건"만 유지한다.
4. **SSE(Server-Sent Events)** — 실시간 알림은 FCM 없이 SSE로 구현한다.

---

## Alternatives Considered

### 1. Pull 방식 (전수 스캔, 기각)

- 매일 특정 시각(예: 오전 5시) 스케줄러가 전 사용자를 조회
- 각 사용자별 오늘의 루틴을 계산 후 알림 발송

**기각 이유:**

- 사용자 수 증가에 따른 DB 및 API 부하 집중
- Notification 서비스가 Routine 서비스에 대량 의존 → 장애 전파 가능성
- 부분 실패 시 재시도 범위 특정 어려움

---

### 2. Kafka (기각)

- 도메인 이벤트 브로드캐스트에는 적합하나, "n분 뒤에 실행" 같은 지연 처리나 실패 시 재시도 보장은 별도 구현 필요
- 알림은 이벤트(무슨 일이 발생했음)가 아닌 **행위(무언가를 해야 함)** — Kafka의 이벤트 소비 모델과 맞지 않음
- 동일 PostgreSQL 트랜잭션에서 동작하지 않으므로, 비즈니스 로직과의 정합성 보장을 위한 Outbox 패턴 등 추가 구현 필요

---

### 3. 직접 큐 구현 (기각)

- visibility 관리, 재시도 로직, due 처리를 직접 구현하면 결과적으로 큐 시스템을 직접 만드는 것과 동일
- 이미 검증된 PGMQ를 선택하는 것이 합리적

---

### 4. FCM (실시간 알림 기각)

- MVP 단계의 웹 전용 서비스에서 FCM을 도입하면 `fcm_tokens` 관리, 토큰 갱신, 디바이스별 처리 등 운영 복잡도가 증가
- SSE로 동일한 실시간 알림 경험 제공 가능

---

## Rationale

### 1. Due 기반 처리

- "보낼 대상을 매번 찾지 말고, 미리 예약해둔다."
- 자정 배치가 다음 발송 시각(vt)을 계산해서 PGMQ에 INSERT
- 스케줄러는 `vt ≤ now()`인 메시지만 꺼내서 발송 → 전수 스캔 불필요

### 2. PGMQ 선택 이유

- PostgreSQL 트랜잭션 안에서 동작 → 메시지 등록과 비즈니스 로직을 같은 트랜잭션으로 묶어 정합성 보장
- 별도 인프라(Kafka, Redis, RabbitMQ 등) 추가 없이 PostgreSQL 안에서 해결
- due 기반 실행, visibility timeout, 재시도 로직을 추상화하여 제공

### 3. 이벤트 vs 행위 구분

- Kafka는 "이벤트(무슨 일이 발생했음)"를 브로드캐스트하는 데 적합
- 알림 예약은 "행위(n시에 알림을 발송해야 함)"에 해당 → 작업 중심의 트랜잭션 모델이 적합
- 실패 시 재처리, 분기 처리가 필요한 경우도 이벤트 소비가 아닌 작업 처리에 해당하므로 PGMQ를 사용한다

### 4. Next-One Chaining

- 미래 알림을 전부 미리 생성하면 DB 용량 급증 및 스케줄 변경 시 다수 레코드 정리 필요
- 항상 "가장 가까운 다음 1건"만 유지하고, 발송 성공 시 다음 1건을 재예약

### 5. SSE (실시간 알림)

- MVP 웹 전용 서비스에서 FCM 없이 SSE로 실시간 알림 구현
- 탭을 닫은 상태의 알림이 필요해지면 Web Push + Service Worker로 확장

---

## Architecture Overview

### 구성 요소

| 구성 요소 | 역할 |
|-----------|------|
| `NOTIFICATION_SCHEDULES` | 발송 예정 알림 1건 저장 (PENDING \| SENT \| FAILED \| CANCELED) |
| `NOTIFICATION_DELIVERY_LOGS` | 실제 발송 이력, 외부 API 응답, 실패 사유 기록 |
| PGMQ Queue | vt(due) 기반 실행 트리거, payload에는 scheduleId만 포함 |

---

### 알림 예약 흐름 (루틴 생성/수정 시)

1. 사용자가 루틴 생성/수정
2. RoutineService가 다음 발송 시각(nextSendAt) 계산
3. `NotificationScheduled` 이벤트 발행
4. NotificationService가 `NOTIFICATION_SCHEDULES`에 upsert(PENDING)
5. PGMQ에 `vt=nextSendAt`으로 enqueue

> 미래의 모든 알림을 생성하지 않는다. 항상 "가장 가까운 다음 1건"만 등록한다.

---

### 알림 발송 흐름 (Next-One Chaining)

```
PGMQ Worker
 └─ read(vt=N초) — 메시지를 N초간 invisible 처리
     └─ scheduleId로 NOTIFICATION_SCHEDULES 조회
         └─ status == PENDING 확인 (멱등성 보장)
             └─ 알림 발송
                 ├─ 성공 시:
                 │    ├─ pgmq.delete(msg_id)        ← 큐에서 제거
                 │    ├─ schedule → SENT
                 │    ├─ NOTIFICATION_DELIVERY_LOGS 기록
                 │    ├─ nextSendAt 계산
                 │    ├─ 새 schedule(PENDING) 생성
                 │    └─ PGMQ에 enqueue(vt=nextSendAt)
                 │
                 └─ 실패 시:
                      ├─ 메시지 삭제 안 함
                      ├─ schedule은 PENDING 유지   ← 재시도 가능하도록
                      ├─ vt 만료 후 메시지 자동 재등장 → 재시도
                      │
                      └─ read_ct > max_retry 초과 시:
                           ├─ schedule → FAILED
                           ├─ NOTIFICATION_DELIVERY_LOGS에 실패 사유 기록
                           └─ pgmq.archive(msg_id) → DLT
```

**예시 — 매일 07:00 루틴 (5분 전 알림)**

- 루틴 생성 시: 다음 06:55 알림 1건 등록
- 06:55 발송 성공 시: 내일 06:55 알림 1건 재등록
- 항상 PENDING은 1건만 유지

---

## Idempotency

- `schedule.status != PENDING`이면 처리하지 않음 → 중복 발송 방지
- 재시도 중 schedule은 PENDING을 유지하므로 재시도 시에도 정상 처리됨
- 최종 실패(max_retry 초과) 후 FAILED로 변경 → 이후 재등장한 메시지는 멱등성 체크에서 차단
- `read_ct` 필드로 현재 재시도 횟수를 추적할 수 있음

---

## Consequences

### Positive

- 전 유저 스캔 제거 → 성능 효율 향상
- 별도 인프라 없이 PostgreSQL 트랜잭션 안에서 정합성 보장
- DB 폭증 방지 (Next-One Chaining)
- 재시도, visibility 관리를 PGMQ에 위임 → 직접 구현 불필요
- Routine 서비스와 Notification 서비스 책임 분리 (장애 격리)

### Negative

- 체인 로직 구현 필요
- sendAt 계산 로직이 중요해짐
- SSE는 탭을 닫은 상태에서 알림 불가 (Web Push로의 확장 필요)

---

## Future Considerations

- 탭 off 상태 알림 필요 시 Web Push + Service Worker 확장
- 재시도 정책 고도화 (Exponential Backoff)
- 대용량 시 PGMQ 부하 모니터링 및 파티셔닝 검토
- 알림 채널별 분리 Worker 도입 (이메일, 푸시 등)
