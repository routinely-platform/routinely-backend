# ADR-0010: 채팅 메시지 저장소 선택

## Status

Accepted

> `(deleted)`, `(deleted)`를 대체하는 파일.
> 초기 MongoDB 채택 결정을 번복하고 PostgreSQL로 확정.

---

## Context

Routinely는 그룹(Challenge) 기반 채팅 기능을 제공한다.

채팅 서비스는 다음과 같은 특성을 가진다:

- 대량의 메시지 생성 (append-heavy workload)
- 메시지는 기본적으로 수정되지 않음 (append-only)
- 방(room) 단위로 정렬 조회
- 최신 메시지 기준 페이징
- 이미지 첨부 등 유연한 메시지 구조 가능성

채팅 메시지를 저장할 데이터베이스로 PostgreSQL과 MongoDB를 비교했다.

---

## Decision

**채팅 메시지를 포함한 모든 ChatService 데이터를 PostgreSQL에 저장한다.**

초기에는 MongoDB 채택을 검토했으나, 운영 복잡도와 트랜잭션 정합성 문제로 PostgreSQL로 확정했다.

---

## Options Considered

### Option 1: MongoDB (초기 검토안, 기각)

#### 장점

- append-heavy workload에 적합
- 유연한 문서 구조로 메시지 타입 확장 용이 (이미지, 시스템 메시지 등)
- roomId 기반 인덱싱 최적화 가능
- 메시지 수 급증 시 수평 확장에 유리

#### 단점

- 운영 DB 이원화 — 인프라, 모니터링, 장애 대응을 두 DB에 대해 각각 유지해야 함
- `chat_rooms.last_message_id` 업데이트와 메시지 저장을 같은 트랜잭션으로 처리 불가
- 관계형 제약조건(FK) 없어 정합성 관리 부담이 애플리케이션 레벨로 이전

---

### Option 2: PostgreSQL (채택)

#### 장점

- 기존 모든 서비스와 동일한 기술 스택 — 운영 복잡도 최소화
- 메시지 저장과 `chat_rooms.last_message_id` 업데이트를 단일 트랜잭션으로 처리 가능
- 기존 인프라, 모니터링 체계 재사용

#### 단점

- 메시지 수 급증 시 파티셔닝/아카이빙 전략이 별도로 필요할 수 있음
- 스키마 유연성이 MongoDB보다 낮음

---

## Rationale

### 1. 운영 복잡도

이미 모든 서비스가 PostgreSQL을 사용하고 있다.
MongoDB를 추가하면 인프라, 모니터링, 장애 대응을 두 DB에 대해 각각 유지해야 한다.

### 2. 트랜잭션 처리 문제

채팅 메시지 저장과 `chat_rooms.last_message_id` 업데이트는 같은 트랜잭션으로 묶어야 정합성이 보장된다.
DB가 분리되면 분산 트랜잭션 문제가 발생하고, 이를 해결하는 추가 복잡도가 생긴다.

### 3. MVP 단계에서의 실익 판단

MongoDB가 의미 있는 시점은 메시지가 수백만 건 이상 쌓이는 서비스에서다.
MVP 규모에서 MongoDB가 주는 이점(스키마 유연성, 수평 확장)은 실질적으로 미미하고,
반면 두 번째 DB 도입으로 생기는 복잡도 증가는 실질적이다.

**결론: MongoDB의 이점 < PostgreSQL 통일의 이점 → PostgreSQL로 확정**

---

## Data Ownership Strategy

ChatService는 모든 데이터를 PostgreSQL 내에서 관리한다.

| 도메인 | 내용 |
|--------|------|
| ChatMessage | 메시지 본문, 첨부 정보, message type, createdAt |
| ChatRoom | 채팅방 메타 정보, ChallengeId 매핑, last_message_id |

---

## Consequences

### Positive

- 단일 DB 운영으로 복잡도 최소화
- 메시지 저장과 메타데이터 업데이트의 트랜잭션 일관성 보장
- 기존 인프라/모니터링 재사용 가능

### Negative

- 향후 메시지 대용량 처리 시 파티셔닝 전략이 별도로 필요
- 메시지 타입 확장 시 스키마 변경 비용 발생 가능

---

## Future Considerations

- 메시지 수 급증 시 room 단위 테이블 파티셔닝 고려
- 오래된 메시지 아카이빙 전략 필요 가능
- 메시지 삭제/수정 정책 명확화 필요
