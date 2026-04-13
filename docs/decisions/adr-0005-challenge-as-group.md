# ADR-0005: Challenge = Group으로 모델링 (별도 Group 도메인 미도입)

- **Status**: Accepted
- **Date**: 2026-02-15
- **Author**: Routinely Project

---

## 1. Context

Routinely는 소규모 그룹 챌린지를 중심으로 루틴을 지속하도록 돕는 서비스이다.

초기 도메인 설계 단계에서 다음 두 가지 모델링 방식이 고려되었다:

### 옵션 A) Group과 Challenge를 분리

- `User`
- `Group`
- `GroupMember`
- `Challenge`
- `ChallengeMember`
- `RoutineTemplate`
- `RoutineExecution`

구조 예:

User ──< GroupMember >── Group ──< Challenge

### 옵션 B) Challenge 자체를 Group 역할로 사용

- `User`
- `Challenge`
- `ChallengeMember`
- `RoutineTemplate`
- `RoutineExecution`

구조 예:

User ──< ChallengeMember >── Challenge

초기 MVP에서는 하나의 그룹에서 하나의 챌린지만 운영하는 구조를 가정하고 있다.

---

## 2. Decision

**Challenge 도메인을 Group의 역할까지 포함하는 단일 개념으로 모델링한다.**

즉,

- 별도의 `Group` 도메인은 두지 않는다.
- `Challenge`가 곧 사용자들이 모이는 “그룹 단위”가 된다.
- `ChallengeMember`를 통해 User와 Challenge의 다대다 관계를 표현한다.

---

## 3. Rationale (결정 이유)

### 3.1 MVP 범위에 적합한 단순성

현재 기획 정책:

- 그룹 = 하나의 챌린지
- 하나의 그룹 안에서 여러 챌린지를 동시에 운영하지 않음

이 상황에서는:

> Group과 Challenge를 분리할 실질적인 필요가 없다.

불필요한 추상화는 모델 복잡도만 증가시킨다.

---

### 3.2 도메인 의미의 일치

Routinely에서 사용자가 체감하는 개념은:

- “러닝 4주 챌린지”
- “아침 기상 챌린지”

사용자는 “그룹”에 들어가는 것이 아니라  
“챌린지에 참여”한다고 인식한다.

즉, 사용자 경험 관점에서도 Challenge가 중심 개념이다.

---

### 3.3 관계 단순화

별도 Group을 둘 경우:

- GroupMember
- ChallengeMember
- Group ↔ Challenge 1:N 관계

등의 추가적인 관계가 필요해진다.

MVP 단계에서는:

- `User ↔ Challenge` 다대다 관계만으로 충분하다.
- `ChallengeMember`가 곧 “그룹 참여”를 의미한다.

---

### 3.4 확장 가능성은 유지

향후 확장 시:

- 하나의 커뮤니티(Group) 안에서 여러 챌린지를 운영해야 한다면
- 상위 개념으로 `Group` 도메인을 추가할 수 있다.

현재 설계는 확장을 막지 않으며,
단지 MVP 범위에서 불필요한 추상화를 제거한 것이다.

---

## 4. Consequences

### 긍정적 영향

- 도메인 모델 단순화
- ERD 구조 간결화
- 서비스 로직 복잡도 감소
- 초기 개발 속도 향상
- 쿼리 단순화

### 부정적 영향

- 향후 “Group 내 다중 Challenge” 요구가 생기면
  모델 변경 필요
- 확장 시 마이그레이션 작업 발생 가능

---

## 5. Domain Summary (현재 모델)

### 핵심 엔티티

- `User`
- `Challenge`
- `ChallengeMember`
- `RoutineTemplate`
- `RoutineExecution`

### 관계

- User ⟷ Challenge (M:N via ChallengeMember)
- Challenge → RoutineTemplate (1:N)
- RoutineTemplate → RoutineExecution (1:N)
- User → RoutineExecution (1:N)

---

## 6. Summary

Routinely MVP 단계에서는  
**Challenge가 곧 Group의 역할을 수행하도록 설계한다.**

이 결정은:

- 도메인 단순성
- MVP 범위 적합성
- 개발 속도
- 사용자 개념 일치성

을 기준으로 채택되었다.

향후 필요 시 상위 Group 도메인을 도입할 수 있도록 확장 여지는 남겨둔다.