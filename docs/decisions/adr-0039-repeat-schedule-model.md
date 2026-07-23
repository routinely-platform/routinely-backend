# ADR-0039: 루틴 반복 스케줄 모델 — 요일 지정(강제) / 빈도(유연) 이원화

- **Status**: Accepted
- **Date**: 2026-07-21
- **Author**: Routinely Project

---

## 1. Context

루틴 반복을 표현하는 두 가지 사용자 의도가 있다.

1. **특정 요일에 한다** — "월·수·금 러닝" (알람 앱형 스케줄)
2. **기간당 N번 한다** — "주 3회 러닝" (빈도)

기존 모델(`repeat_type` {DAILY, DAILY_N, WEEKLY, WEEKLY_N, MONTHLY_N} + `repeat_value`)은
빈도만 표현하고 **요일 개념이 없었다.** `WEEKLY`조차 "어느 요일"인지 정의가 없어, 사용자가 원하는
요일 지정 스케줄을 담지 못했다. 나아가 "오늘 이 루틴이 due한가"가 결정되지 않아 실행 기록 설계가 표류했다.

핵심 질문은 두 가지였다.
- 요일 지정을 넣을 것인가?
- 넣는다면 강제(그 요일 안 하면 결석)인가, 선호(알림용, 아무 날이나 완료 가능)인가?

---

## 2. Decision

### 2.1 반복을 유형(scheduleType)으로 이원화한다

| 유형 | 분류 | 의미 | 필드 |
|---|---|---|---|
| `DAILY` | 지정형(강제) | 매일 | — |
| `SPECIFIC_DAYS` | 지정형(강제) | 특정 요일 | `days_of_week` (비트마스크) |
| `WEEKLY_COUNT` | 빈도형(유연) | 주 N회 | `target_count` |
| `MONTHLY_COUNT` | 빈도형(유연) | 월 N회 | `target_count` |

**강제성은 "요일 설정 여부"가 아니라 "유형"이 결정한다.**
- 지정형(`DAILY`/`SPECIFIC_DAYS`): 지정 요일이 스케줄. 그 날 미완료는 결석(MISSED). 다른 날은 이 루틴과 무관.
- 빈도형(`WEEKLY_COUNT`/`MONTHLY_COUNT`): 아무 날이나 수행 가능. 달성 = 완료 수 / N. 특정일 MISSED 없음.

### 2.2 선호(시각·요일)는 인스턴스가 소유한다 (ADR-0035 확장)

- **강제 요일**(`routine_templates.days_of_week`) = 템플릿(정의)
- **선호 요일**(`routines.preferred_days`) · **선호 시각**(`routines.preferred_time`) = 인스턴스(개인화, soft)

빈도형 루틴에서 멤버가 지정하는 요일은 **선호(soft)** 다 — 알림/표시에만 쓰이고 완료를 제약하지 않는다.
"주 3회, 선호 요일 월화수"인데 목·금·토에 수행해도 3회를 채우면 달성이다. 선호 요일 개수와 목표 횟수 N은
서로 독립이다(주 3회에 선호 요일 5개를 골라도 된다 — 순수 편의).

### 2.3 챌린지는 SPECIFIC_DAYS를 쓸 수 없다

챌린지 루틴은 멤버 생활 패턴이 달라 특정 요일 강제가 부적절하다(ADR-0035가 선호 시각에 대해 내린 결론과
동일한 논리). 챌린지는 `DAILY`/`WEEKLY_COUNT`/`MONTHLY_COUNT`만 허용하며, 멤버는 인스턴스에
선호 시각·요일(soft)만 설정한다.

---

## 3. Rationale

### 3.1 두 패러다임은 실제로 다른 사용자 니즈다

"월수금 고정"과 "주 3회 아무 때나"는 습관 형성에서 별개의 모델이며, 업계 표준 습관 앱(Streaks, Loop
Habit Tracker, Habitica, HabitNow)이 공통적으로 두 모드를 사용자 선택으로 제공한다. 하나로 강제하면
사용자 경험이 훼손된다.

### 3.2 강제/선호를 유형으로 가르면 의미가 단일해진다

"요일이 곧 강제"(지정형)와 "요일은 알림 힌트"(빈도형+선호)를 유형으로 분리하므로, 같은 요일 데이터가
문맥에 따라 다른 의미를 갖는 모호함이 없다. UI에서도 "특정 요일에 해요" vs "일주일에 N번(아무 때나)"로
명확히 표현된다.

### 3.3 "오늘의 루틴" 결정 가능성

이원화로 "오늘 이 루틴이 대상인가"가 결정된다.
- 지정형: 오늘 ∈ 지정/전체 요일이면 오늘 대상
- 빈도형: 항상 대상(진행률 "이번 주 N/M"로 표시), 완료는 아무 날이나

---

## 4. 스키마

```sql
-- routine_templates (정의)
schedule_type VARCHAR(20) NOT NULL   -- DAILY | SPECIFIC_DAYS | WEEKLY_COUNT | MONTHLY_COUNT
days_of_week  SMALLINT NULL           -- SPECIFIC_DAYS 전용, 비트마스크(bit0=월 … bit6=일)
target_count  INT NULL                -- WEEKLY_COUNT/MONTHLY_COUNT 전용
-- days_of_week는 월~일 7비트만 유효하므로 1~127로 강제(0=빈 집합, 128↑/음수 차단)
CONSTRAINT ck_rt_schedule CHECK (
  (schedule_type='DAILY'          AND days_of_week IS NULL AND target_count IS NULL) OR
  (schedule_type='SPECIFIC_DAYS'  AND days_of_week IS NOT NULL AND days_of_week BETWEEN 1 AND 127 AND target_count IS NULL) OR
  (schedule_type IN ('WEEKLY_COUNT','MONTHLY_COUNT') AND target_count IS NOT NULL AND target_count >= 1 AND days_of_week IS NULL)
)

-- routines (인스턴스)
preferred_days SMALLINT NULL           -- 선호 요일(soft) 비트마스크, 빈도 유형 알림/표시용
CONSTRAINT ck_routines_preferred_days CHECK (preferred_days IS NULL OR preferred_days BETWEEN 1 AND 127)
```

기존 데이터 매핑(V4): DAILY→DAILY, WEEKLY_N→WEEKLY_COUNT, MONTHLY_N→MONTHLY_COUNT,
WEEKLY→WEEKLY_COUNT(count=1), DAILY_N→DAILY(하루 N회 미지원 → 수렴).

---

## 5. Consequences

### 긍정적
- 알람 앱형 강제 스케줄 + 유연 빈도 둘 다 지원 → 사용자 니즈 충족, 업계 표준 부합
- 강제/선호가 유형으로 명확히 갈려 스키마·UI 의미가 단일
- "오늘의 루틴" due 판정이 결정적 → 실행 기록(#57) 설계의 기반

### 부정적
- 템플릿 스키마 변경 → #55(머지됨)·challenge-service 반영 필요 (본 이슈 #149에서 처리)
- 반복 유형이 늘어 검증/표시 분기 증가

### 향후 확장 (기록만)
- **챌린지 멤버 자기 요일 강제화**: 공유 목표(주 N회)는 유지하되, 멤버가 고른 요일을 "본인에게는 강제"로
  personalize (soft/hard 플래그). MVP는 soft로 간다.
- **지정형 건너뛰기/휴가**: SPECIFIC_DAYS에서 특정 날을 결석으로 세지 않고 스트릭을 유지하는 예외
  (Streaks/Habitica 유사 기능).

---

## 6. 관련 결정

- ADR-0035: 선호 시각 인스턴스 소유 (본 결정은 선호 요일로 확장, 챌린지 SPECIFIC_DAYS 금지의 근거)
- ADR-0026: 챌린지 루틴 고정 정책 (챌린지는 공동 약속만, 요일 강제는 예외 없이 불가)
- ADR-0034: 챌린지 생성 시 루틴 템플릿 비동기 생성 (challenge.created payload에 scheduleType/targetCount)
- ADR-0038(예정): 실행 기록 저장 전략 — 본 결정의 due 판정 위에 sparse 저장을 얹는다 (#57 재설계)
