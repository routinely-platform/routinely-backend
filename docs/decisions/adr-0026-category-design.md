# ADR-0026: 카테고리 설계 (별도 테이블 / routine-service 소유 / challenge 비정규화)

- **Status**: Accepted
- **Date**: 2026-05-19

---

## 1. Context

챌린지와 루틴 템플릿에 카테고리(운동, 독서 등) 개념이 필요하다.
카테고리를 어떻게 모델링하고, 어느 서비스가 소유하며, 서비스 간 접근을 어떻게 처리할지 결정해야 한다.

검토한 설계 결정은 세 가지다:

1. **카테고리 저장 방식**: 문자열 열거형 vs 별도 테이블
2. **소유 서비스**: Category 전용 서비스 신설 vs routine-service 소유
3. **challenges 테이블 저장 방식**: 정규화(gRPC 조회) vs 비정규화(사본 보유)

---

## 2. Decision

### 2-1. 별도 테이블로 관리

문자열 enum 컬럼이 아닌 `categories` 테이블로 분리한다.

```sql
categories
- id
- code (UNIQUE)        -- EXERCISE, READING 등
- name
- icon
- display_order
- is_active
```

**근거**: 아이콘/표시 순서 등 메타데이터 부착이 필요하고, 코드와 함께 Flyway 시드로 버전 관리할 수 있다.

### 2-2. routine-service가 소유

별도 Category 서비스는 신설하지 않는다. routine-service가 `categories` 테이블을 소유한다.

**근거**:
- 카테고리는 정적 참조 데이터 12~20개 수준 — 전용 서비스는 과도한 설계
- 루틴 템플릿이 카테고리를 직접 참조하는 도메인 상위 개념
- Challenge는 루틴을 활용하는 쪽이므로 `Challenge → Routine` 의존 방향이 자연스럽다

### 2-3. challenges.category_code 비정규화 보유

`challenges` 테이블에 `category_code` 컬럼을 보유한다 (비정규화 사본).

**Source of truth**: `routine_templates.category_code`
**비정규화 사본**: `challenges.category_code`

**근거**: 챌린지 목록의 카테고리 필터링이 가장 빈번한 쿼리인데, 정규화하면:
- Cross-service JOIN 불가
- 페이지네이션/정렬 결합 시 성능 붕괴
- gRPC 2단계 호출 필요

챌린지 생성 시점에 한 번 복사되고 이후 **immutable**하게 다루므로 동기화 비용이 거의 없다.

### 2-4. 단일 카테고리 (NOT NULL)

루틴과 챌린지 모두 카테고리는 **1개, NOT NULL**로 제한한다.

**근거**: 분류 체계는 MECE(Mutually Exclusive, Collectively Exhaustive)할 때 가장 강력하다. 다중 카테고리는 필터링 중복 노출, 분류 의미 희석 문제를 유발한다. 여러 속성 표현은 태그(v2)로 분리한다.

미분류 방지: `"기타(ETC)"` 카테고리를 두어 모든 데이터가 분류 체계 안에 들어오도록 보장한다.

### 2-5. 운영 방식: 시스템 제공 카테고리만

사용자 생성 카테고리를 허용하지 않는다. Flyway 시드(`V2__seed_categories.sql`)로 코드와 함께 버전 관리한다.

**기본 12개 카테고리**: 운동, 독서, 공부/학습, 어학, 건강, 수면, 식습관, 명상/마음챙김, 자기계발, 생산성, 취미, 금연/금주

---

## 3. Service Interaction

Challenge-service는 카테고리에 gRPC로 접근한다.

```
ChallengeService → RoutineService (gRPC: ListCategories)
                → Redis 캐싱 (TTL 24h)
```

카테고리 목록 조회 (프론트 드롭다운):
```
GET /api/v1/categories → Gateway → routine-service (직접 REST)
```

---

## 4. Consequences

### 긍정적 영향
- 챌린지 목록 카테고리 필터링이 challenge-service 내부에서 완결
- 카테고리 메타데이터 관리가 코드와 함께 버전 관리됨
- 분류 체계의 일관성 보장 (MECE)

### 부정적 영향
- `challenges.category_code`와 `routine_templates.category_code`의 비정규화 유지 필요
- 챌린지 생성 시 categoryCode 유효성 검증을 위한 gRPC 호출 (Redis 캐싱으로 완화)
