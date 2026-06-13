# ADR-0027: routine_templates FK 방향 결정 (challenge_id in routine_templates)

- **Status**: Accepted
- **Date**: 2026-05-19

---

## 1. Context

챌린지와 루틴 템플릿은 1:1 관계다.
1:1 관계에서 FK는 어느 쪽에 두어도 기술적으로 가능하지만, MSA에서는 **서비스 의존성 방향**을 결정하는 의미를 갖는다.

두 가지 옵션을 검토했다.

**옵션 A**: `challenges.routine_template_id` (Challenge 쪽에 FK)
- 챌린지 생성 트랜잭션과 템플릿 생성이 분리 → Saga/Outbox 필요
- `routine_template_id`가 soft FK — 매번 gRPC 검증 필요

**옵션 B**: `routine_templates.challenge_id` (RoutineTemplate 쪽에 FK) — **채택**
- Routine 서비스 내부 트랜잭션으로 template 생성이 닫힘
- Challenge 서비스는 routine 도메인을 몰라도 됨

---

## 2. Decision

`routine_templates` 테이블에 `challenge_id` 컬럼을 둔다.

```sql
routine_templates
- id              PK
- user_id         NOT NULL    -- 템플릿 생성자 (개인/챌린지 모두 항상 존재)
- challenge_id    NULL        -- 챌린지 템플릿일 때만 set
- category_code   NOT NULL
- ...

UNIQUE(challenge_id)  -- 챌린지당 템플릿 1개 보장
```

타입 구분:
- `challenge_id IS NULL` → 개인 템플릿
- `challenge_id IS NOT NULL` → 챌린지 템플릿

---

## 3. Rationale

### 3-1. 폴리모픽 연관관계 안티패턴 제거

초기 설계에서는 `owner_type` / `owner_id` 폴리모픽 패턴을 사용했다.

```sql
-- 안티패턴 (제거됨)
owner_id   BIGINT NOT NULL   -- user_id 또는 challenge_id
owner_type VARCHAR(20)       -- 'PERSONAL' | 'CHALLENGE'
```

**문제점**:
- FK 제약 불가 (DB 레벨 참조 무결성 불가)
- 인덱스 효율 저하 (owner_type 컬럼 추가 조건 필요)
- 스키마 자기설명력 부족

**해결**: `user_id NOT NULL` + `challenge_id NULL` 명시적 분리

### 3-2. MSA에서 FK 방향 = 서비스 의존성 방향

1:1 관계에서 FK 위치 결정 기준 우선순위:

1. **서비스 간 의존성 방향** (도메인 생명주기)
2. **트랜잭션 경계** (한 서비스 안에서 닫히는가)
3. **데이터 소유권** (source of truth)
4. ~~조회 빈도/성능~~ (BFF aggregation으로 해결)

`routine_template`이 더 근본적인 개념이다 (개인 루틴은 challenge 없이도 존재).
의존 방향 `Challenge → Routine`이 자연스러우므로 `routine_templates`에 `challenge_id`를 둔다.

### 3-3. 트랜잭션 경계

챌린지 생성 시 루틴 템플릿 생성 흐름:
```
challenge.created 이벤트 → routine-service Kafka Consumer
                         → routine_templates INSERT (challenge_id=X)
```

Routine 서비스 내부 트랜잭션으로 완결된다. 분산 트랜잭션이 필요 없다.

### 3-4. 조회 성능은 BFF로 해결

```
GET /challenges/5
    ├─ gRPC: Challenge.GetChallenge(5)
    └─ gRPC: Routine.GetTemplateByChallengeId(5)  ← challenge_id 인덱스 O(1)
    ↓ 병렬 호출, 응답 합쳐 내려줌
```

`challenge_id` 인덱스만 있으면 조회는 빠르다.

---

## 4. Consequences

### 긍정적 영향
- `user_id`, `challenge_id` 명시적 분리 → 스키마 자기설명력 향상
- FK 제약으로 routine_templates ↔ challenges 참조 무결성 가능 (MSA soft FK)
- Routine 서비스 내부 트랜잭션으로 템플릿 생성 완결
- Challenge 서비스는 routine 도메인을 몰라도 됨

### 부정적 영향
- 챌린지 상세 조회 시 BFF에서 2회 gRPC 호출 필요
- `GetChallengeContext` gRPC 요청 파라미터가 `routine_template_id` → `challenge_id`로 변경됨 (ADR-0026 참고)
