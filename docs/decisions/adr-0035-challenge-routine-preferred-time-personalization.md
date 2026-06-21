# ADR-0035: 챌린지 루틴 선호 시각 개인화 — routines 인스턴스 소유

- **Status**: Accepted
- **Date**: 2026-06-21
- **Author**: Routinely Project

---

## 1. Context

챌린지 루틴은 참여 멤버 전원이 동일한 템플릿(`routine_templates`)을 공유한다. 반복 유형·횟수는 고정이다 (ADR-0026).

여기서 **선호 수행 시각(`preferred_time`, 리마인더 알림 발송 기준)**을 어디에 둘지 결정해야 한다. 기존 스키마는 `routine_templates.preferred_time` 한 곳에만 컬럼을 두고 있었다.

문제는 두 가지다.

1. **알림 수신자 부적합** — 방장이 챌린지 생성 시 선호 시각을 정하면, 생활 패턴이 다른 다른 멤버 전원에게 부적절한 시각의 알림이 발송된다. "주 3회 러닝"이라는 약속은 공유하더라도, 누구는 새벽 6시, 누구는 밤 10시에 뛴다.

2. **1 템플릿 : N 인스턴스 구조와 충돌** — `routines.routine_template_id`는 UNIQUE가 아닌 FK다. 챌린지 루틴은 템플릿 1개를 멤버 수만큼의 `routines`가 참조한다. 시각을 템플릿에 두면 N명이 단일 값을 강제로 공유하게 된다.

---

## 2. Decision

**선호 수행 시각은 `routine_templates`(정의)가 아니라 `routines`(인스턴스)가 소유한다.**

- `routine_templates`에서 `preferred_time` 컬럼을 **제거**한다.
- `routines`에 `preferred_time TIME NULL` 컬럼을 **추가**한다.
- 챌린지 생성 시 선호 시각을 입력받지 않는다. `challenge.created` 이벤트 payload에도 포함하지 않는다 (ADR-0034).
- 챌린지 루틴 인스턴스는 `challenge.started` 수신 시 `preferred_time = NULL`로 생성되며, 멤버가 이후 `PATCH /api/v1/routines/{routineId}`로 본인 시각을 설정한다.
- 개인 루틴도 동일하게 인스턴스(`POST /api/v1/routines`)에서 선호 시각을 받는다. 템플릿은 정의(`title`, `repeat_type`, `repeat_value`)만 갖는다.
- `preferred_time`이 `NULL`인 루틴은 리마인더 알림 발송 대상에서 제외한다.

---

## 3. Rationale

### 3.1 "무엇을/얼마나"는 약속, "언제 알림"은 개인

ADR-0026이 정의한 분리 원칙의 자연스러운 연장이다. 챌린지의 공동 약속은 루틴 종류·반복 횟수·기간이고, 수행 시각·알림은 개인 일과에 종속되는 영역이다.

### 3.2 도메인 정합성

`preferred_time`은 "이 사람이 몇 시에 알림 받을지"이므로 본질적으로 인스턴스 속성이다. 1 템플릿 : N 인스턴스 구조에서 인스턴스마다 값이 다를 수 있으므로 `routines`에 두는 것이 일관적이다. 이는 챌린지뿐 아니라 한 개인 템플릿으로 시기를 달리해 여러 번 루틴을 시작하는 경우에도 성립한다.

### 3.3 스키마 의미의 단일화

템플릿에 두고 "개인이면 값, 챌린지면 NULL"로 운용하면 컬럼이 조건부 의미를 갖는다(스키마 냄새). 인스턴스로 일원화하면 컬럼 의미가 단일해진다.

---

## 4. Consequences

### 긍정적 영향

- 멤버별로 알림 시각을 자유롭게 설정 → 알림 적합성 향상
- 스키마 의미 단일화 — 조건부 NULL 규칙 제거
- 개인/챌린지 루틴이 동일한 방식(인스턴스 소유)으로 처리되어 로직 통일

### 부정적 영향

- 개인 루틴 생성 흐름 변경 — 선호 시각 입력이 `POST /routine-templates`에서 `POST /routines`로 이동
- 인스턴스 선호 시각 수정용 `PATCH /api/v1/routines/{routineId}` API 신설 필요
- 멤버가 명시적으로 설정하기 전까지 챌린지 루틴은 알림이 없음 → 설정을 유도하는 UX(챌린지 상세의 "내 알림 시간 설정" 액션) 필요

### 구현 영향

- **DDL**: `routine_templates.preferred_time` 제거, `routines.preferred_time` 추가
- **notification-service**: 리마인더 발송 기준을 `routine_templates.preferred_time` → `routines.preferred_time`로 변경, `NULL`은 대상 제외
- **routine-service**: `routines/today` 등 응답의 `preferredTime` 출처가 `routines.preferred_time`으로 변경

---

## 5. 관련 결정

- ADR-0026: 챌린지 루틴 고정 정책 (본 결정은 그 예외 — 시각만 개인화)
- ADR-0034: 챌린지 생성 시 루틴 템플릿 비동기 생성 (`challenge.created` payload에서 `preferredTime` 제외)
- ADR-0017: 알림 스케줄링 전략 (발송 기준 시각 소스 변경)
