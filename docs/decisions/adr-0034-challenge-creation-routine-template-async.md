# ADR-0034: 챌린지 생성 시 루틴 템플릿 생성 — 비동기 이벤트 처리

- **Status**: Accepted
- **Date**: 2026-06-12
- **Author**: Routinely Project

---

## 1. Context

챌린지 생성 시 routine-service에 챌린지 연결 루틴 템플릿(`routine_templates`)이 생성되어야 한다. 챌린지 연결은 별도 `owner_type` 컬럼이 아니라 `routine_templates.challenge_id`(UNIQUE)로 표현한다 (ADR-0037 routine_templates FK 방향 결정).

- 템플릿의 `schedule_type`, `target_count` 등은 챌린지 생성 시 결정·고정된다 (ADR-0036, ADR-0039)
- 챌린지 시작 시 발행되는 `challenge.started` 이벤트 페이로드가 `routineTemplateId`를 포함하므로, **시작 전에 템플릿이 반드시 존재해야 한다** (ADR-0032)
- ADR-0032는 챌린지 **시작** 시점의 `routines` / `routine_executions` 생성 방식을 결정했으나, **생성** 시점의 템플릿 생성 방식은 미결정 상태였다

검토한 두 가지 방식:

1. **동기 방식** — 챌린지 생성 트랜잭션 중 gRPC로 routine-service에 템플릿 생성 요청
2. **비동기 방식** — `challenge.created` 이벤트 발행 (Outbox + Kafka + Inbox)

동기 방식의 직관적 매력은 "루틴 템플릿 없는 챌린지" 상태가 생기지 않는다는 점이다.
챌린지와 루틴은 도메인적으로 한 묶음인데, 챌린지만 생성되고 루틴이 없는 상태가 이상해 보이기 때문이다.

---

## 2. Decision

**`challenge.created` 이벤트 발행(Outbox + Kafka + Inbox)으로 비동기 처리한다.**

---

## 3. Rationale

### 3.1 동기 gRPC도 원자성을 보장하지 못한다

두 서비스의 DB에 쓰는 작업은 본질적으로 분산 트랜잭션이다. gRPC를 써도 불일치는 제거되지 않는다.

| 시나리오 | 결과 |
|---|---|
| gRPC로 템플릿 먼저 생성 → 챌린지 로컬 커밋 실패 | 고아 템플릿 발생 → 보상 트랜잭션 필요 |
| 챌린지 먼저 커밋 → gRPC 호출 실패 | 템플릿 없는 챌린지 → 결국 재시도 메커니즘 필요 |

동기 호출은 불일치 윈도우를 **좁힐 뿐 제거하지 못하면서**, 실패 경로마다 보상/재시도 로직을 직접 구현해야 한다.
반면 Outbox는 챌린지 INSERT + 이벤트 기록이 **하나의 로컬 트랜잭션**으로 원자적이며,
"템플릿이 결국 반드시 생성됨"을 패턴 차원에서 보장한다 (ADR-0012, ADR-0013).

### 3.2 WAITING 상태가 충분한 시간 버퍼다

템플릿이 실제로 필요한 시점은 두 가지뿐이다.

| 필요 시점 | 시간 여유 |
|---|---|
| `challenge.started` 페이로드 구성 (시작 스케줄러) | 생성일 → 시작일 사이 (최소 수 시간~수 일) |
| 챌린지 상세 조회 시 루틴 정보 노출 | 생성 직후 ms~초 단위 윈도우만 존재 |

챌린지는 항상 `WAITING` 상태로 생성되고(시작일은 생성일 이후), Outbox poller + Kafka 지연은 수 초 이내다.
시작 시점에 템플릿이 없을 가능성은 사실상 0이다.

### 3.3 가용성 격리

동기 방식이면 routine-service 장애 시 **챌린지 생성 API 자체가 실패**한다.
비동기 방식은 챌린지 생성은 성공하고, 템플릿은 routine-service 복구 후 Inbox 재처리로 생성된다.

### 3.4 판단 기준 — "응답을 완성하는 데 그 결과가 필요한가"

같은 챌린지 생성 흐름 안에서도 통신 방식이 갈린다 (ADR-0007 원칙의 구체화).

| 작업 | 응답에 필요한가 | 방식 |
|---|---|---|
| `categoryCode` 유효성 검증 | 필요 — 검증 없이는 생성 가부 응답 불가 | gRPC `ListCategories` (#117) |
| 루틴 템플릿 생성 | 불필요 — 생성 응답에 템플릿 ID 미포함 | `challenge.created` 이벤트 |

---

## 4. 트레이드오프 및 보완

### 부정적 영향

- 생성 직후 상세 조회 시 루틴 정보가 비어 있는 ms~초 단위 윈도우 존재
- Inbox Processor 장애 시 템플릿 생성 지연 (retry로 복구)

### 보완책

- 클라이언트는 상세 화면에서 루틴 영역만 비워두는 graceful degradation 적용 (ADR-0032 §10과 동일 전략)
- 방어적 안전장치로, 시작 스케줄러가 `challenge.started` 발행 전 템플릿 존재를 확인하는 검증을 둘 수 있다 (선택)

---

## 5. 구현 노트

- `docs/requirements/event-spec.md`에 `challenge.created` 토픽 명세 추가 필요 (현재 미정의)
- 페이로드에는 routine-service가 추가 RPC 없이 자기 완결적으로 템플릿을 생성할 수 있도록
  `challengeId`, `routineTitle`, `scheduleType`, `targetCount`, `startedAt`, `endedAt` 등을 포함한다
  (선호 수행 시각은 멤버별 `routines` 인스턴스에서 설정하므로 페이로드에 포함하지 않는다 — ADR-0035)
- `ChallengeService.createChallenge()`의 `TODO: #48` 주석은 이슈 번호가 낡았다
  (실제 #48은 Redis ZSET 랭킹 이슈) — 구현 이슈 생성 시 번호 정정 필요

---

## 6. Related ADRs

- [ADR-0007: 서비스 간 통신 전략](adr-0007-communication-strategy.md) — Command → gRPC / Event → Kafka 원칙
- [ADR-0012: Outbox 패턴](adr-0012-outbox-pattern.md) — 발행 정합성
- [ADR-0036: 챌린지 루틴 고정 정책](adr-0036-challenge-routine-fixed-policy.md) — 템플릿은 생성 시 결정·고정
- [ADR-0037: routine_templates FK 방향](adr-0037-routine-template-fk-direction.md) — 챌린지 연결을 owner_type이 아닌 challenge_id로 표현
- [ADR-0032: 챌린지 시작 시 루틴 생성](adr-0032-challenge-start-routine-creation.md) — 시작 시점 routines 비동기 생성 (본 ADR과 대칭)
