# ADR-0011: 통계 집계 전략 — ReportService 미도입 및 RoutineService 직접 조회

## Status

Accepted

> 초기 결정(Event-Driven Pre-Aggregation + ReportService)을 번복하고,
> MVP 단계에서는 RoutineService 내 On-Demand 집계 쿼리로 확정.

---

## Context

Routinely는 다음과 같은 통계 기능을 제공한다:

- 개인 달성률 (일/주/월)
- 연속 성공 일수 (스트릭)
- 그룹 평균 달성률
- 그룹 내 개인 랭킹

초기 설계에서는 이벤트 기반 사전 집계(Event-Driven Pre-Aggregation) + 별도 ReportService를 고려했으나,
MVP 규모와 시스템 복잡도를 기준으로 재검토하여 결정을 번복했다.

---

## Decision

**별도 ReportService를 도입하지 않는다.**
통계 기능은 RoutineService 내에서 On-Demand 집계 쿼리로 직접 제공한다.

---

## Alternatives Considered

### Option 1: Event-Driven Pre-Aggregation + ReportService (초기 검토안, 기각)

- RoutineService가 루틴 완료 시 Kafka 이벤트 발행
- ReportService가 이벤트를 소비하여 사전 집계 후 저장
- 조회 시 미리 집계된 데이터를 반환

**기각 이유:**

| 추가 요소 | 비고 |
|-----------|------|
| Kafka 이벤트 소비 로직 | 이미 이벤트 시스템이 충분히 복잡함 |
| 집계용 테이블 관리 | 별도 스키마 설계 및 동기화 필요 |
| 멱등성 처리 | 이벤트 중복 처리 방어 로직 필요 |
| 데이터 동기화 문제 | 원본(RoutineService)과 집계본(ReportService) 간 정합성 관리 |
| 운영 복잡도 증가 | 서비스 추가 = 배포, 모니터링, 장애 대응 단위 추가 |

MVP 수준에서는 이점보다 복잡도 증가가 더 크다.

---

### Option 2: RoutineService 직접 집계 쿼리 (채택)

- 별도 서비스 없이 RoutineService가 `routine_executions` 테이블을 직접 집계
- PostgreSQL `GROUP BY / COUNT` 쿼리로 처리

**채택 이유:**

1. **데이터 단일 출처**: 통계에 필요한 모든 데이터가 `routine_executions`에 존재 → 별도 동기화 불필요
2. **현재 규모에서 집계 비용이 크지 않음**: GROUP BY 수준의 쿼리로 충분히 처리 가능
3. **시스템 복잡도 최소화**: 이미 Kafka, PGMQ, WebSocket, 다수의 마이크로서비스가 존재 → 추가 서비스 없이 유지
4. **구현 속도**: MVP 기능을 빠르게 제공 가능

---

## API 설계

```
GET /users/{userId}/stats
GET /challenges/{challengeId}/stats
GET /challenges/{challengeId}/ranking
```

---

## 집계 쿼리 예시

### 개인 달성률

```sql
SELECT
  COUNT(*) FILTER (WHERE status = 'DONE')::float / COUNT(*) AS success_rate
FROM routine_executions
WHERE user_id = :userId
  AND exec_date BETWEEN :startDate AND :endDate;
```

### 스트릭

- 하루 단위 성공 여부 기반 순차 계산
- 이전 날짜 상태를 기준으로 현재 streak_count 도출
- 누적 증가 방식이 아닌 재계산 가능 구조로 설계

### 그룹 랭킹

- 각 멤버의 달성률을 계산 후 정렬
- 현재 규모에서는 실시간 계산으로 충분
- 향후 Redis Sorted Set으로 전환 가능

---

## Consequences

### Positive

- 서비스 구조 단순화 (ReportService 미도입)
- 데이터 정합성 문제 없음 (단일 출처)
- 구현 및 운영 복잡도 최소화

### Negative

- 사용자 수 증가 시 집계 쿼리 비용 증가 가능
- 랭킹 계산이 요청마다 발생 → 트래픽 집중 시 부하 가능성

---

## 향후 확장 전략

사용자 수 증가나 조회 트래픽 증가로 집계 비용이 커질 경우 단계적으로 확장한다:

| 단계 | 전략 |
|------|------|
| 1단계 | 인덱스 최적화 및 쿼리 튜닝 |
| 2단계 | Redis 기반 랭킹 캐싱 도입 |
| 3단계 | CQRS Read Model 구축 |
| 4단계 | Kafka 이벤트 기반 집계 + ReportService 분리 |

현재는 단순 구조로 시작하고, 필요 시 확장 가능한 구조로 설계한다.

---

## Architectural Principle

과도한 설계보다 현재 요구사항에 맞는 단순한 구조를 우선한다.

> "데이터 원천이 하나이고, 집계 비용이 크지 않다면 별도 서비스는 오버엔지니어링이다."
