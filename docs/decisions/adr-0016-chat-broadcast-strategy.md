# ADR-0016: Chat 브로드캐스트 전략

## Status
Accepted

---

## Context

Routinely는 그룹(Challenge) 기반 실시간 채팅 기능을 제공한다.

채팅 서비스는 다음과 같은 요구사항을 가진다:

- WebSocket 기반 실시간 메시지 전달
- 그룹(room) 단위 메시지 브로드캐스트
- 멀티 인스턴스 확장 가능성
- 메시지 순서 보장 필요
- 메시지 영속 저장 (PostgreSQL)

단일 인스턴스 환경에서는
메모리 기반 브로드캐스트만으로 충분하다.

하지만 향후 ChatService가 수평 확장될 경우,
다음 문제가 발생한다:

- 인스턴스 A에 연결된 클라이언트
- 인스턴스 B에 연결된 클라이언트
- A에서 수신된 메시지를 B의 세션에 전달해야 함

따라서 멀티 인스턴스 브로드캐스트 전략이 필요했다.

---

## Decision

Routinely는 Chat 브로드캐스트를 위해  
**Kafka 기반 이벤트 브로드캐스트 전략을 채택한다.**

구조는 다음과 같다:

1. 클라이언트 → WebSocket → ChatService 인스턴스
2. 메시지 PostgreSQL 저장
3. Kafka publish (chat.message.created)
4. 모든 ChatService 인스턴스가 해당 이벤트 consume
5. 각 인스턴스는 자신이 보유한 WebSocket 세션에 브로드캐스트

---

## Why Not Direct In-Memory Broadcast?

단일 인스턴스에서는 가능하지만:

- 수평 확장 시 메시지 전달 불가
- 세션 공유 필요 (Sticky Session 의존)
- 로드밸런싱 제약 발생

확장성을 위해 중앙 브로커 기반 구조를 선택한다.

---

## Why Kafka?

Kafka는 다음 장점을 가진다:

- 다중 구독자(fan-out)에 적합
- 높은 처리량
- 파티션 기반 순서 보장
- 이벤트 리플레이 가능

특히 roomId를 파티션 키로 사용하면
방 단위 메시지 순서를 보장할 수 있다.

---

## Broadcast Flow

### 1️⃣ 메시지 수신

```plaintext
Client → WebSocket → ChatService Instance A