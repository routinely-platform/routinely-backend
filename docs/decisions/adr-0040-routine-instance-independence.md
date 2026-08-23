# ADR-0040: 루틴 인스턴스 독립 — 템플릿은 생성 도구다

- **Status**: Accepted
- **Date**: 2026-08-22
- **Author**: Routinely Project

---

## 1. Context

`routines`(인스턴스)는 **"이 루틴이 무엇인가"를 하나도 저장하지 않는다.** 가진 것은 기간·선호 시각·
선호 요일·활성 여부뿐이고, 제목·카테고리·반복 스케줄은 전부 `routine_templates`를 조인해 읽는다.

```
routines: routine_template_id, user_id, challenge_id,
          started_at, ended_at, preferred_time, preferred_days, is_active
```

ADR-0038이 sparse 저장을 도입하면서 이 구조가 문제를 드러냈다. **미완료 상태(PENDING/MISSED)를 저장하지
않고 조회 시 파생**하는데, 그 파생이 **현재 템플릿의 `schedule_type`을 읽는다.** 과거의 판정 결과가
템플릿의 현재 값에 종속된다.

월수금(`SPECIFIC_DAYS`) 루틴을 6주 완주한 사용자가 템플릿을 `DAILY`로 수정하면:

| | 수정 전 | 수정 후 |
|---|---|---|
| 지난 6주의 화·목·토·일 | 이 루틴과 무관 | **전부 MISSED** |
| 달성률 | 100% (13/13) | **42% (13/31)** |

사용자는 앞으로의 계획을 바꿨을 뿐인데 **이미 지킨 약속이 소급해서 깨진다.**

문제의 뿌리는 파생 로직이 아니라 **모델이다.** 이름은 "템플릿"인데 실제로는 **공유 정의**로 동작한다.
템플릿을 고치면 그것으로 만든 모든 루틴이 따라 바뀌므로, 템플릿과 인스턴스가 독립적이지 않다.

핵심 질문: **템플릿과 루틴은 어떤 관계여야 하는가?**

---

## 2. Decision

**템플릿은 순수한 생성 도구다. 루틴은 생성 시점에 정의를 복사해 자기완결적으로 존재한다.**

Word 문서 템플릿과 그것으로 만든 문서의 관계와 같다. 템플릿을 고쳐도 이미 만든 문서는 바뀌지 않고,
템플릿을 지워도 문서는 남는다.

### 2.1 루틴 시작 시 정의 5필드를 복사한다

| 필드 | 복사 |
|---|---|
| `title` · `category_code` | ✅ |
| `schedule_type` · `days_of_week` · `target_count` | ✅ |

`routine_template_id` FK는 **출처 추적용으로 유지**하되 **NULL을 허용한다**(§2.4). "이 템플릿으로 몇 번
시작했나"를 세는 데 쓰고, **조회·판정 경로에서는 사용하지 않는다.** 값을 복사한다고 관계를 끊을 이유는 없다.

### 2.2 템플릿은 언제나 자유롭게 수정·삭제할 수 있다

제목·카테고리·주기 전부 제약 없이 고친다. **기존 루틴에 영향이 0이므로 잠글 이유가 없다.**
템플릿 삭제도 마찬가지다 — 그 템플릿으로 시작한 루틴은 그대로 돌아간다.

### 2.3 루틴의 수정 규칙

| 필드 | 수정 | 조건 |
|---|---|---|
| `title` · `category_code` | ✅ 언제나 | 그 루틴 하나만 바뀐다 |
| `schedule_type` · `days_of_week` · `target_count` | ⚠️ 조건부 | **그 루틴에 완료 기록이 0건일 때만** |
| `preferred_time` · `preferred_days` | ✅ 언제나 | 판정에 쓰이지 않는 soft 값 (ADR-0035, ADR-0039) |

주기 잠금이 사라지는 게 아니라 **템플릿에서 루틴으로 자리를 옮긴다.** 값이 어느 테이블에 있든,
완료 기록이 있는 스케줄을 고치면 그 루틴의 과거가 깨지는 것은 같다.

### 2.3.1 챌린지 루틴은 정의를 수정할 수 없다

위 표는 **개인 루틴** 기준이다. 챌린지 루틴은 ADR-0036(참여자 개인화 미허용)에 따라 정의를 전혀 고칠 수 없다.

| 필드 | 개인 루틴 | 챌린지 루틴 |
|---|:---:|:---:|
| `preferred_time` · `preferred_days` | ✅ | ✅ (ADR-0035 — 알림 시각은 개인 영역) |
| `title` · `category_code` | ✅ | **❌ 403** |
| `schedule_type` · `days_of_week` · `target_count` | ⚠️ 완료 0건일 때만 | **❌ 403** |

> ⚠️ **새로 생기는 검증 지점이다.** 지금 `PATCH /api/v1/routines/{routineId}`는 `preferredTime`·
> `preferredDays`만 받고, 둘 다 챌린지 루틴에 허용되는 값이라 별도 분기가 없다. 본 결정으로 이 엔드포인트에
> `title`·주기 3필드가 추가되면 **멤버가 자기 챌린지 루틴을 개인화할 수 있게 되어 ADR-0036이 뚫린다.**
> 필드별로 갈라 챌린지 루틴은 정의 필드를 거부해야 한다.

### 2.4 템플릿 없이도 루틴을 만들 수 있다

루틴이 자기 정의를 전부 가지므로 **템플릿을 거치지 않을 이유가 없다.** `routine_template_id`를
**NULL 허용**으로 바꾸고, 루틴 생성 API가 두 경로를 받는다.

```jsonc
// 경로 1 — 템플릿에서 복사
POST /api/v1/routines
{ "routineTemplateId": 1, "startedAt": "...", "endedAt": "...", "preferredTime": "07:00:00" }

// 경로 2 — 정의를 직접 입력 (템플릿 없음)
POST /api/v1/routines
{ "title": "아침 달리기", "categoryCode": "EXERCISE",
  "scheduleType": "SPECIFIC_DAYS", "daysOfWeek": ["MON","WED","FRI"],
  "startedAt": "...", "endedAt": "...", "preferredTime": "07:00:00" }
```

`routineTemplateId`와 정의 필드는 **둘 중 하나만** 보낸다(둘 다 또는 둘 다 없음이면 400).
경로 1은 템플릿 값을 복사하고, 경로 2는 요청 값을 그대로 저장한다. **저장된 뒤의 루틴은 두 경로가
완전히 동일하다** — `routine_template_id`가 NULL인지 아닌지만 다르다.

이로써 **템플릿의 역할이 명확해진다: 반복해서 시작할 루틴을 저장해 두는 곳.** 한 번만 할 루틴을
만들려고 템플릿 목록을 오염시키지 않아도 된다.

> "이 루틴을 템플릿으로도 저장" 옵션은 API에 넣지 않는다. 필요하면 클라이언트가
> `POST /routine-templates` → `POST /routines` 두 번 호출하면 된다. 엔드포인트에 플래그를 더하는 것보다
> 조합이 단순하다.

---

## 3. Rationale

### 3.1 "템플릿"이라는 이름이 이미 답이다

템플릿의 정의는 **"복제의 원본"** 이지 "공유 참조"가 아니다. 이름과 동작이 어긋나 있었고,
§1의 버그는 그 어긋남이 sparse 저장을 만나 드러난 것이다. 파생 로직을 고치는 것보다 모델을
이름에 맞추는 편이 근본적이다.

### 3.2 같은 템플릿으로 서로 다른 주기의 루틴을 돌릴 수 있다

독립의 실질적 이득이다.

```
템플릿 A "아침 달리기 / 월수금"
  ├─ 6/1 시작 → 루틴 A′ (월수금)   6월 달성률 100%
  └─ 9/1 시작 → 루틴 A″ (월수금)    9월 달성률  85%

7/15에 템플릿 A를 "매일"로 고쳐도
  → A′·A″ 는 그대로. 10/1에 시작하는 A‴ 만 매일로 생성된다
```

각 루틴의 달성률이 **그 루틴이 한 약속을 기준으로** 따로 계산된다. "주 3회 시절 100%"와
"매일 시절 60%"가 섞이지 않는다.

### 3.3 파생 로직이 단순해진다

현재 `RoutineExecutionService`는 파생을 위해 템플릿을 한 번 더 조회해 `routineId → template` 맵을
만들고, `RoutineService`도 제목 표시를 위해 같은 일을 한다. **두 조회와 매핑이 모두 사라진다.**
`ExecutionScheduleDeriver`는 `Routine` 하나만 받으면 된다. 스키마는 늘고 코드는 준다.

### 3.4 챌린지 루틴 고정 정책이 DB 레벨로 내려간다

ADR-0036은 "챌린지 루틴은 참여자가 못 바꾼다"를 서비스 레이어 검증으로 지켰다. 정의가 인스턴스로
복사되면 **멤버가 참여한 시점의 약속이 각자의 행에 박힌다.** 방장이 나중에 무엇을 하든 진행 중 멤버의
달성률 분모가 흔들리지 않는다 — ADR-0027의 캡 계산이 기대는 "고정 분모" 전제가 구조로 성립한다.

### 3.5 중복이 아니라 시점 스냅샷이다

같은 이름의 컬럼이 두 테이블에 생기지만 **담는 사실이 다르다.**

| | 뜻 | 시제 |
|---|---|---|
| `routine_templates.schedule_type` | "앞으로 이렇게 만들 것이다" | 생성 설정 |
| `routines.schedule_type` | "이 루틴은 이 약속으로 시작했다" | **과거 사실** |

정규화가 금지하는 것은 *같은 사실*의 중복이다. 이 둘은 값이 우연히 같을 뿐 별개의 사실이며,
`products.price`와 `order_items.price`의 관계와 같다 — 상품 가격이 올라도 지난 주문 금액은 바뀌지 않는다.

**중복이 위험한 조건은 "원본이 바뀌면 사본도 따라가야 할 때"다.** 본 결정은 정반대로 원본이 바뀌어도
사본을 일부러 두는 것이므로 동기화 자체가 없고, 따라서 동기화 버그도 없다.

이 저장소에 같은 패턴이 이미 둘 있다.

| 컬럼 | 스키마 주석 |
|---|---|
| `feed_cards.routine_title` | "피드 생성 시점의 루틴명 스냅샷 — 이후 템플릿 수정에 영향 없음" |
| `challenges.category_code` | "categories.code 비정규화 사본 (생성 시점 1회 복사, 이후 불변)" |

### 3.6 기각한 대안

**대안 A — 현행 유지.** §1의 소급 문제가 그대로 남는다.

**대안 B — 주기 3필드만 복사하고 제목은 템플릿 참조 유지.**
과거 보호에는 충분하다. 그러나 제목이 여전히 소급되므로 "템플릿을 고쳤는데 진행 중 루틴 이름이 바뀐다"는
어긋남이 남고, **필드마다 소유가 갈려 모델을 설명하기 어려워진다.** 전부 복사하면 "루틴은 시작 시점의
정의를 갖는다"는 한 문장으로 끝난다. 루틴 제목을 고치고 싶으면 루틴을 직접 고치면 되므로 잃는 것도 없다.

**대안 C — 템플릿의 주기 수정을 완료 기록 발생 시 잠근다.**
스키마 변경이 없어 가장 싸다. 그러나 **주기를 바꾸려면 템플릿을 복제해야 하므로 템플릿이 계속 늘어난다.**
유사 제목이 쌓여 결국 목록에 아카이브 개념이 필요해지고, 복제 플로우를 화면이 떠안는다. 무엇보다
§3.2(같은 템플릿, 다른 주기의 루틴 여럿)가 불가능하다.

**대안 D — 템플릿 버저닝.** 인스턴스가 특정 버전을 FK로 가리킨다. 중복 없이 같은 결과를 얻지만
수정마다 행이 쌓여 보존·정리 정책이 따라온다. 사본 5컬럼으로 얻는 결과가 같아 MVP 규모에 과하다.

---

## 4. 스키마

```sql
-- V6__routine_definition_snapshot.sql
ALTER TABLE routines ADD COLUMN title         VARCHAR(100);
ALTER TABLE routines ADD COLUMN category_code VARCHAR(30);
ALTER TABLE routines ADD COLUMN schedule_type VARCHAR(20);
ALTER TABLE routines ADD COLUMN days_of_week  SMALLINT NULL;
ALTER TABLE routines ADD COLUMN target_count  INT      NULL;

-- 기존 행은 참조 중인 템플릿에서 복사해 백필
UPDATE routines r
SET title         = t.title,
    category_code = t.category_code,
    schedule_type = t.schedule_type,
    days_of_week  = t.days_of_week,
    target_count  = t.target_count
FROM routine_templates t
WHERE r.routine_template_id = t.id;

-- 템플릿 없이 만든 루틴을 허용한다 (§2.4)
ALTER TABLE routines ALTER COLUMN routine_template_id DROP NOT NULL;

ALTER TABLE routines ALTER COLUMN title         SET NOT NULL;
ALTER TABLE routines ALTER COLUMN category_code SET NOT NULL;
ALTER TABLE routines ALTER COLUMN schedule_type SET NOT NULL;

-- routine_templates.ck_rt_schedule 미러링
-- ⚠️ 두 제약은 짝이다. 한쪽을 고치면 다른 쪽도 함께 고친다.
ALTER TABLE routines ADD CONSTRAINT ck_routines_schedule CHECK (
    (schedule_type = 'DAILY'
        AND days_of_week IS NULL AND target_count IS NULL)
    OR (schedule_type = 'SPECIFIC_DAYS'
        AND days_of_week IS NOT NULL AND days_of_week BETWEEN 1 AND 127 AND target_count IS NULL)
    OR (schedule_type IN ('WEEKLY_COUNT', 'MONTHLY_COUNT')
        AND target_count IS NOT NULL AND target_count >= 1 AND days_of_week IS NULL)
);

COMMENT ON COLUMN routines.routine_template_id IS '출처 템플릿 ID (선택) — 템플릿 없이 직접 만든 루틴이면 NULL. 조회·판정에는 사용하지 않는다';
COMMENT ON COLUMN routines.title         IS '루틴 시작 시점의 제목 사본 — 이후 템플릿 수정에 영향 없음';
COMMENT ON COLUMN routines.schedule_type IS '루틴 시작 시점의 반복 유형 사본 — 완료 기록 발생 후 변경 금지';
```

### `days_of_week`와 `preferred_days`가 한 테이블에 공존한다

이름이 비슷해 혼동하기 쉬우므로 의미를 못박는다.

| 컬럼 | 성격 | 완료를 제약하나 | 쓰는 유형 |
|---|---|---|---|
| `days_of_week` | **강제**(hard) — 스케줄 그 자체 | **제약한다.** 지정 요일이 아니면 완료 거부, 안 하면 MISSED | `SPECIFIC_DAYS` |
| `preferred_days` | **선호**(soft) — 알림·표시용 | 제약하지 않는다 | 빈도형 (ADR-0039) |

---

## 5. Consequences

### 긍정적
- **템플릿과 루틴이 이름대로 동작한다.** 템플릿 수정·삭제가 기존 루틴에 영향을 주지 않는다.
- 같은 템플릿으로 서로 다른 주기의 루틴을 여러 개 돌릴 수 있다(§3.2).
- **파생·조회에서 템플릿 조인이 통째로 사라진다.** `resolveTemplates()`·`resolveTemplateTitles()` 제거.
- 템플릿 잠금·복제 플로우가 불필요해져 화면이 단순해진다.
- 챌린지 루틴 고정(ADR-0036)과 달성률 고정 분모(ADR-0027)가 구조로 보장된다.
- 소프트 삭제된 템플릿을 파생이 읽어야 하는 기존의 미묘한 의존이 사라진다.
- **템플릿 없이 루틴을 만들 수 있다**(§2.4). 한 번만 할 루틴 때문에 템플릿 목록이 오염되지 않고,
  템플릿의 역할이 "반복해서 시작할 루틴 보관함"으로 명확해진다.

### 부정적
- **마이그레이션 1건 + 컬럼 5개 복제.** 챌린지 루틴은 멤버 수만큼 같은 정의가 복제된다(컬럼 5개라 비용은 미미하다).
- **CHECK 제약을 두 테이블에 미러링해야 한다.** 한쪽만 고치면 어긋난다 — 컴파일러가 잡지 못하는 결합이라 양쪽 마이그레이션에 짝을 주석으로 명시했다.
- **챌린지 루틴 제목을 방장이 바꾸는 기능(`challenge-update-policy.md`의 미정 항목)은 N개 행 업데이트가 된다.** 지금까지는 템플릿 1행이면 됐다. 그 항목을 정할 때 이 비용을 함께 본다.
- **`PATCH /routines/{routineId}`에 챌린지 루틴 분기가 새로 필요하다**(§2.3.1). 이 검증을 빠뜨리면 ADR-0036이 뚫린다.
- 루틴 제목이 인스턴스마다 갈릴 수 있다. 템플릿 제목을 고쳐도 진행 중 루틴은 옛 이름이므로, **템플릿 수정 화면에 "이미 시작한 루틴의 이름은 바뀌지 않습니다"를 안내**해야 한다.

### 구현 영향 (#57)

**#57 머지 전에 함께 처리한다.** 머지 후에 바꾸면 마이그레이션·서비스·테스트(154개)를 한꺼번에 손대게 된다.

| 대상 | 변경 |
|---|---|
| `Routine` 엔티티 | 정의 5필드 추가 |
| `Routine.forPersonal()` | 템플릿 정의를 복사해 받는다 |
| `ExecutionScheduleDeriver` | `RoutineTemplate` → `Routine`을 받는다 |
| `RoutineExecutionService.resolveTemplates()` | **제거** |
| `RoutineService.resolveTemplateTitles()` | **제거** |
| `RoutineService.deactivate()` 주변 | 템플릿 삭제 시 루틴을 건드리지 않는다 |
| `PATCH /api/v1/routines/{routineId}` | `title`·`categoryCode`·주기 3필드를 받는다. 주기는 완료 기록 0건일 때만 |
| `RoutineExecutionRepository` | `existsByRoutineId(Long)` 추가 (주기 잠금 판정) |
| `POST /api/v1/routines` | `routineTemplateId` 경로와 정의 직접 입력 경로를 모두 받는다 (배타 검증) |
| `challenge.started` 소비자 (ADR-0032, 미구현) | 멤버 인스턴스 생성 시 챌린지 템플릿 정의를 복사한다 |

> **챌린지 템플릿은 기존대로 유지한다**(ADR-0034 `challenge.created` → 템플릿 생성). 멤버 인스턴스가
> 정의를 다 갖더라도 템플릿이 필요한 이유가 셋 있다 — ① 챌린지 **시작 전**에도 루틴 정의를 보여줘야 하고
> (상세 화면), ② 멤버 인스턴스가 **복사해 올 원본**이 필요하며, ③ 중간 참여(v2 #118) 시 새 멤버도 같은
> 원본에서 복사한다.
>
> 챌린지 템플릿은 이미 routine-service API에서 조회·수정·삭제가 모두 403이고(`validateNotChallengeLinked`),
> `PATCH /challenges/{id}`에서도 `scheduleType`·`targetCount`가 항상 변경 불가다. **따라서 §1의 소급 문제는
> 애초에 개인 템플릿에서만 발생 가능했고, 본 결정이 챌린지 쪽에 새 위험을 만들지 않는다.**

### 설계 이력

본 결정은 세 안을 거쳐 확정됐다. ① 현행 유지 → ② 주기 3필드만 스냅샷 → ③ 템플릿 주기 잠금 →
**④ 정의 전체 복사(본 결정)**. ②③은 §1의 증상만 막았고, ④에 와서야 원인(템플릿이 이름과 달리
공유 정의로 동작한다)을 고쳤다. **증상을 막는 안들이 더 싸 보였지만, 모델을 바로잡는 안이 결과적으로
코드도 화면도 더 단순하게 만들었다.**

---

## 6. 관련 결정

- **ADR-0038**: sparse 저장 — 본 결정이 지키려는 전제("MISSED는 계산된 값")를 도입한 결정.
- **ADR-0039**: 반복 스케줄 모델 이원화 — 복사 대상인 주기 3필드를 정의한 결정.
- **ADR-0036**: 챌린지 루틴 고정 — 본 결정으로 DB 레벨 보장이 추가된다.
- **ADR-0035**: 선호 시각을 인스턴스로 — "정의는 공유, 개인 사정은 인스턴스"의 원형. 본 결정은 정의까지 인스턴스로 옮긴다.
- **ADR-0037**: 루틴 템플릿 FK 방향 — 본 결정으로 FK의 역할이 "조회 경로"에서 "출처 추적"으로 바뀐다.
- **ADR-0027**: 달성률 캡 — "고정 분모" 전제가 본 결정으로 구조 보장된다.
