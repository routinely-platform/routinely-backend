# ADR-0044: 챌린지 루틴 정의의 소유권 — challenge-service

## Status

Accepted (2026-08-24)

**Supersedes** — [ADR-0034](adr-0034-challenge-creation-routine-template-async.md) (챌린지 생성 시 루틴 템플릿 비동기 생성),
[ADR-0037](adr-0037-routine-template-fk-direction.md) (`routine_templates.challenge_id` FK 방향)

**개정** — [ADR-0032](adr-0032-challenge-start-routine-creation.md) §7 이벤트 페이로드

---

## 1. Context

챌린지의 루틴 정의(제목·반복 주기·목표 횟수)를 **routine-service의 `routine_templates`가 소유**해 왔다.
챌린지를 만들면 `challenge.created` 이벤트가 발행되고, routine-service가 `challenge_id`를 채운
템플릿을 만든다(ADR-0034·ADR-0037). 챌린지가 시작되면 그 템플릿을 읽어 멤버별 루틴을 만든다(ADR-0032).

**[ADR-0040](adr-0040-routine-instance-independence.md)이 이 구조의 전제를 바꿨다.**
템플릿은 이제 **순수한 생성 도구**이고, 루틴이 정의 5필드를 복사해 자기완결적으로 존재한다.
그러자 챌린지 템플릿이 무엇을 하고 있는지가 불분명해졌다.

### 1.1 확인된 사실 — 넷 중 둘은 이미 문제로 드러나 있었다

**① 챌린지 템플릿은 사용자에게 보이지 않는다**

```java
// RoutineTemplateRepository
List<RoutineTemplate> findAllByUserIdAndChallengeIdIsNullAndIsDeletedFalseOrderByIdDesc(Long userId);
```

모든 사용자 조회에 `challenge_id IS NULL`이 붙는다. "이 템플릿으로 시작" 버튼도 없다.
**템플릿의 유일한 목적인 "다시 시작할 틀"을 챌린지 템플릿은 수행하지 않는다.**

**② ADR-0040 이후 시작하고 나면 아무도 읽지 않는다**

루틴이 정의를 복사하므로 챌린지 템플릿은 시작 시점 한 번 읽히고 수명 내내 죽은 데이터다.

**③ 【이미 발생】 방장이 루틴 제목을 수정할 수 없다**

```java
// ChallengeService.validateRoutineUpdateNotSupported()
// TODO: routine-service 개발 이후 gRPC 통신으로 루틴 템플릿 수정 처리
throw new BusinessException(VALIDATION_FAILED, "루틴 정보 수정은 아직 지원하지 않습니다.");
```

화면 명세는 **방장이 혼자이고 시작 전이면 루틴 제목을 바꿀 수 있다**고 확정했는데,
정의가 남의 서비스에 있어 gRPC를 새로 뚫어야 한다.

**④ 【이미 발생】 챌린지 화면에 루틴 정의를 표시할 수 없다**

`ChallengeListResponse` · `ChallengeDetailResponse`에 루틴 필드가 없고 `challenges` 테이블에도 없다.
그런데 화면 명세는 **둘러보기 카드 · 참여 확인 모달 · 챌린지 상세** 세 곳에서 루틴 정의를 요구한다.
셋 다 **참여를 결정하는 정보**다.

**⑤ ADR-0034의 전제가 코드와 다르다**

ADR-0034는 *"`challenge.started` 페이로드가 `routineTemplateId`를 포함하므로 시작 전에 템플릿이
반드시 존재해야 한다"* 고 적었으나, 실제 `ChallengeStartedEvent`에는 그 필드가 없다.
**"템플릿이 먼저 있어야 한다"는 논거 자체가 성립하지 않는다.**

### 1.2 읽는 쪽과 쓰는 쪽의 비대칭

| 정의가 필요한 곳 | 서비스 | 빈도 |
|---|---|---|
| 둘러보기 목록(N개) · 내 챌린지 목록 | challenge | 높음 |
| 챌린지 상세 · 참여 확인 모달 | challenge | 높음 |
| 방장 루틴 제목 수정 | challenge | 드묾 |
| **멤버 루틴 생성** | **routine** | **챌린지당 1회** |

**routine-service가 정의를 필요로 하는 순간은 시작할 때 한 번뿐이다.**

---

## 2. Decision

**챌린지의 루틴 정의는 challenge-service가 소유한다.**

1. `challenges`에 정의 컬럼을 둔다 — `routine_title` · `schedule_type` · `target_count`
2. `challenge.started` 이벤트 페이로드에 **정의를 실어** 보낸다
3. routine-service는 그 페이로드만으로 멤버별 루틴을 만든다 — **추가 조회 없음**
4. **챌린지 템플릿을 만들지 않는다.** `challenge.created` → 템플릿 생성 흐름을 제거한다
5. `routine_templates`는 **사용자가 저장한 틀 전용** 테이블이 된다 — `challenge_id` 컬럼과 `uq_rt_challenge_id` 제거

`days_of_week`는 두지 않는다 — 챌린지 루틴은 `SPECIFIC_DAYS`를 쓸 수 없다(ADR-0036).

---

## 3. Rationale

### 3.1 챌린지 루틴 정의는 "템플릿"이 아니라 "참여 조건"이다

ADR-0036이 **"챌린지 루틴은 방장이 정하고 멤버는 바꿀 수 없다"** 고 정한 순간,
그 정의는 루틴의 틀이 아니라 **챌린지의 불변 속성**이 됐다.
사용자는 참여 전에 "무엇을 하게 되는가"를 봐야 하고, 방장은 시작 전에 그 조건을 고칠 수 있어야 한다.
**읽기·수정 흐름이 전부 챌린지 화면과 챌린지 정책에 붙어 있다.**

### 3.2 자주 읽는 쪽이 남의 서비스를 부르지 않는다

정의를 routine-service에 두면 challenge-service가 목록·상세·모달마다 gRPC를 호출해야 하고,
목록은 N개라 **배치 조회 RPC를 새로 만들어야** 한다.
그 결과가 *"챌린지 화면에 챌린지의 핵심 조건을 보여주기 위해 다른 서비스를 호출한다"* 라서 보상이 작다.

반대로 challenge-service가 소유하면 **읽기는 전부 로컬**이고, routine-service는 시작 시 이벤트를 한 번 받는다.
**드물게 필요한 쪽이 페이로드를 받는 구조가 자주 필요한 쪽이 동기 호출하는 구조보다 단순하다.**

### 3.3 ADR-0032의 원칙을 오히려 지킨다

ADR-0032 §7은 이렇게 적었다.

> Routine 서비스가 **추가 RPC 없이 자기 완결적으로** 처리할 수 있도록 필요한 모든 정보를 담는다.

그런데 `routineTemplateId`를 담는 방식은 결국 **템플릿을 조회해야** 자기완결이 된다.
정의를 직접 담으면 이 원칙이 문자 그대로 지켜진다.

### 3.4 challenge-service는 이미 그 값을 받고 있다

`CreateChallengeRequest`가 `routineTitle`·`scheduleType`·`targetCount`를 받고
`COUNT_REQUIRED_TYPES` 검증까지 한다. 다만 **저장하지 않고 흘려보낼 뿐**이다.

```java
// ChallengeService — 현행 주석
// 루틴 필드는 challenge-service가 저장하지 않고 그대로 전달한다.
```

**검증 중복은 새로 생기는 게 아니라 이미 있다.** 저장만 추가된다.

---

## 4. 트레이드오프

| 잃는 것 | |
|---|---|
| `#132`·`#133` 되돌리기 | `challenge.created` Outbox 발행과 소비(템플릿 생성). 이미 머지됨 |
| 스케줄 검증 중복 | 두 서비스가 `WEEKLY_COUNT`면 `targetCount` 필수 같은 규칙을 각자 갖는다 (§3.4 — 이미 중복) |
| 이벤트 페이로드 증가 | `challenge.started`에 3필드 추가. 멤버 상한이 20명이라 부담 없다 |

| 얻는 것 | |
|---|---|
| 문제 ③④ 해소 | 로컬 데이터가 되어 gRPC 두 개가 불필요해진다 |
| 테이블 단일 목적화 | `routine_templates`에서 `challenge_id`·UNIQUE·모든 필터가 사라진다 |
| 이벤트 흐름 감소 | `challenge.created` 토픽·Outbox·Inbox 처리가 통째로 없어진다 |

---

## 5. 확장 여지 — 챌린지 루틴 복수화

**현재는 챌린지당 루틴이 하나다**(ADR-0036). 그래서 `challenges`에 3컬럼을 직접 둔다.

복수 루틴이 필요해지면 **challenge-service 내부에 `challenge_routine_definitions` 테이블로 분리**한다.
서비스 경계는 그대로이므로 이 결정은 유지되고, 저장 형태만 바뀐다.

> 지금 미리 테이블을 나누지 않는 이유 — 하나뿐인 것을 위해 조인을 만들면
> 모든 조회에 비용이 붙는데, 복수화가 확정된 계획이 아니다.

---

## 6. 구현 노트

### 6.1 스키마

```sql
ALTER TABLE challenges ADD COLUMN routine_title VARCHAR(100);
ALTER TABLE challenges ADD COLUMN schedule_type VARCHAR(20);
ALTER TABLE challenges ADD COLUMN target_count  INT NULL;
-- 기존 행은 routine-service의 챌린지 템플릿에서 백필한 뒤 NOT NULL 전환
-- ck_challenges_schedule — routine_templates.ck_rt_schedule을 미러링하되
--   SPECIFIC_DAYS는 허용하지 않는다 (ADR-0036)

ALTER TABLE routine_templates DROP CONSTRAINT uq_rt_challenge_id;
ALTER TABLE routine_templates DROP COLUMN challenge_id;
```

> ⚠️ `ck_challenges_schedule`과 `ck_rt_schedule`은 **짝이다.** 한쪽을 고치면 다른 쪽도 본다 —
> 컴파일러가 잡지 못하는 결합이다.

### 6.2 이벤트

`challenge.started` 페이로드 (ADR-0032 §7 개정)

```json
{
  "eventId": "...", "occurredAt": "...",
  "challengeId": 10,
  "startedAt": "2026-03-01",
  "endedAt": "2026-03-31",
  "routineTitle": "아침 러닝 3km",
  "categoryCode": "EXERCISE",
  "scheduleType": "DAILY",
  "targetCount": null,
  "members": [ { "userId": 1 }, { "userId": 2 } ]
}
```

- `routineTemplateId` **제거**
- routine-service는 이 페이로드만으로 멤버별 `routines`를 만든다.
  `routine_template_id`는 **NULL**이다(ADR-0040이 nullable로 만들었다)

`challenge.created` → 루틴 템플릿 생성 흐름은 **제거**한다.

### 6.3 응답 DTO

`ChallengeListResponse` · `ChallengeDetailResponse`에 루틴 정의를 노출한다.
화면 명세가 요구하는 세 곳(둘러보기 카드 · 참여 확인 모달 · 상세)이 이걸 쓴다.

### 6.4 방장 수정

`validateRoutineUpdateNotSupported()`의 `// TODO: gRPC`를 제거하고 **로컬 UPDATE**로 바꾼다.
수정 가능 조건은 기존과 같다 — `WAITING` 상태 + 활성 멤버 1명(방장 혼자).

---

## 7. Related ADRs

| | |
|---|---|
| ADR-0040 | 루틴 인스턴스 독립 — 이 결정의 출발점 |
| ADR-0036 | 챌린지 루틴 고정 — 정의가 챌린지의 불변 속성인 근거 |
| ADR-0032 | 챌린지 시작 시 루틴 생성 — §7 페이로드가 개정된다 |
| ADR-0034 | **Superseded** — 템플릿 생성 자체가 없어진다 |
| ADR-0037 | **Superseded** — `routine_templates.challenge_id`가 없어진다 |
| ADR-0012 · 0013 | Outbox · 멱등성 — `challenge.started` 경로는 그대로 유지 |
