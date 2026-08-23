# ADR-0027: 달성률 계산 — repeat_type별 주별/월별 캡 적용

- **Status**: **Superseded by ADR-0043**

> ADR-0039(반복 스케줄 이원화)로 `WEEKLY_N`/`MONTHLY_N` 명칭이 바뀌고 `SPECIFIC_DAYS`가 추가되면서
> 본 문서의 표에 요일 지정형 분모 정의가 없다. ADR-0041(무기한 루틴)로 "전체 기간" 분모도 성립하지 않는다.
> **ADR-0043이 대체한다.** 기간별 캡 규칙 자체는 ADR-0043이 계승한다.
- **Date**: 2026-05-21
- **Author**: Routinely Project

---

## 1. Context

챌린지 달성률은 `accepted_count / total_scheduled × 100`으로 정의된다.
`repeat_type`이 `WEEKLY_N` 또는 `MONTHLY_N`인 경우, 한 주(월)에 약속한 횟수 이상을 수행했을 때
초과분을 인정할지 여부를 결정해야 한다.

예시: "주 3회" 챌린지에서 1주차에 7회 수행한 경우

- **캡 없음**: 7회 전부 인정 → 1주차만으로도 전체 달성률이 왜곡 가능
- **캡 있음**: 3회만 인정 → 나머지 4회는 이월 없음

---

## 2. Decision

**`WEEKLY_N` / `MONTHLY_N` 루틴에 대해 주별/월별 캡을 적용한다.**

### repeat_type별 계산 공식

| repeat_type | 분모 (total_scheduled) | 분자 (accepted_count) |
|---|---|---|
| `DAILY` | 챌린지 총 일수 | `status = COMPLETED`인 routine_executions 수 |
| `WEEKLY_N` | 챌린지 주 수 × repeat_value | 각 주별 `min(주별 완료 수, repeat_value)` 합계 |
| `MONTHLY_N` | 챌린지 월 수 × repeat_value | 각 월별 `min(월별 완료 수, repeat_value)` 합계 |

### 달성률

```
achievement_rate = accepted_count / total_scheduled × 100
```

---

## 3. Rationale

### 3.1 약속의 의미 보존

"주 3회"는 **매주** 3회를 수행하겠다는 약속이다.
한 주에 몰아서 하고 다른 주에 0회이면 약속을 지킨 것이 아니다.
캡을 적용함으로써 "꾸준함"이라는 챌린지의 본질을 달성률에 반영한다.

### 3.2 예시 (4주 챌린지, 주 3회, total_scheduled = 12)

| 사용자 | 주차별 완료 | 인정(캡 적용) | 달성률 |
|---|---|---|---|
| A (매주 정확히 3회) | 3, 3, 3, 3 | 3+3+3+3 = 12 | 100% |
| B (몰아하기) | 5, 2, 3, 2 | min(5,3)+min(2,3)+min(3,3)+min(2,3) = 3+2+3+2 = 10 | 83.3% |
| C (매주 1회) | 1, 1, 1, 1 | 1+1+1+1 = 4 | 33.3% |

B 사용자가 A보다 총 완료 횟수(12 vs 12)는 같지만 약속을 지킨 주(3주 vs 4주)가 다르므로
캡 적용 시 달성률이 낮게 산출된다.

---

## 4. 구현 — 쿼리 패턴

### WEEKLY_N

```sql
WITH weekly_counts AS (
    SELECT
        DATE_TRUNC('week', scheduled_date) AS week_start,
        COUNT(*) FILTER (WHERE status = 'COMPLETED') AS completed_count
    FROM routine_executions
    WHERE routine_id = :routine_id
    GROUP BY DATE_TRUNC('week', scheduled_date)
)
SELECT
    SUM(LEAST(completed_count, :repeat_value)) AS accepted_count,
    :total_weeks * :repeat_value              AS total_scheduled
FROM weekly_counts;
```

`LEAST(completed_count, repeat_value)`가 캡 적용의 핵심이다.

### MONTHLY_N

동일 구조에서 `DATE_TRUNC('week', ...)` → `DATE_TRUNC('month', ...)`로 변경.

### DAILY

```sql
SELECT
    COUNT(*) FILTER (WHERE status = 'COMPLETED') AS accepted_count,
    :total_days                                  AS total_scheduled
FROM routine_executions
WHERE routine_id = :routine_id;
```

---

## 5. API 응답 필드 정의

랭킹/통계 조회 응답에서 아래 필드명을 사용한다:

| 필드 | 의미 |
|---|---|
| `acceptedCount` | 캡 적용 후 인정된 횟수 |
| `totalScheduled` | 예정 횟수 (분모) |
| `achievementRate` | `acceptedCount / totalScheduled × 100` |

> DB 컬럼명(`completed_count`)과 API 필드명(`acceptedCount`)이 다름에 주의.
> `challenge_member_summary.completed_count`는 캡 적용 후 저장되는 인정 횟수이다.

---

## 6. Consequences

### 긍정적 영향

- 달성률이 "꾸준함"을 측정하는 지표로서 의미를 가짐
- 랭킹 조작(몰아하기) 방지

### 부정적 영향

- `DAILY` 대비 `WEEKLY_N`/`MONTHLY_N` 집계 쿼리가 복잡함
- `total_scheduled` 계산 시 챌린지 기간과 repeat_value를 함께 알아야 함

---

## 7. 관련 결정

- ADR-0026: 챌린지 루틴 고정 정책 (고정 분모를 보장하는 전제 조건)
- ADR-0028: 이벤트 기반 집계 (캡 적용 계산을 summary 갱신 시점에 수행)
