# ADR-0016: Chat 브로드캐스트 전략

## Status
Accepted (2026-09-13 보완)

> **2026-09-13 보완 — 컨슈머 그룹을 인스턴스마다 따로 둔다**
>
> `event-spec.md`가 소비 그룹을 "전 인스턴스 동일 그룹"으로 적고 있었다. 같은 그룹이면 Kafka는 파티션 하나를
> **한 인스턴스에만** 준다 — 인스턴스 B에 붙은 사람은 A가 받은 메시지를 영영 못 받아 **이 ADR의 결정 4번이 성립하지 않는다.**
>
> - 그룹 ID를 인스턴스마다 다르게 둔다 — `chat-service.broadcast.{instanceId}`. 모든 인스턴스가 모든 메시지를 받는다
> - 방 단위 순서는 파티션 키 `roomId`로 **인스턴스마다** 지켜진다
> - **브로드캐스트 소비에는 Inbox를 쓰지 않는다.** `chat_inbox`는 인스턴스들이 함께 쓰는 표라 먼저 받은 인스턴스가 행을 넣으면
>   나머지는 중복으로 건너뛴다. 브로드캐스트는 DB에 쓰지 않아 중복 전달이 해롭지 않다 — 클라이언트가 `messageId`로 거른다
> - `auto.offset.reset=latest` — 새 인스턴스는 과거 메시지를 다시 뿌리지 않는다. 연결이 끊긴 사이 놓친 메시지는 재연결 뒤 REST로 채운다
> - `@RetryableTopic`·DLT를 붙이지 않는다 — 인스턴스마다 재시도 토픽이 생기고, 늦게 도착한 브로드캐스트는 쓸모가 없다
> - 탈퇴 시 구독 끊기도 이 경로를 탄다 — `challenge.member.left`를 처리한 인스턴스가 탈퇴 SYSTEM 메시지를 발행하고,
>   받은 **모든 인스턴스**가 그 사용자의 구독을 정리한다(ADR-0046 §6)

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

```plaintext
Client ──STOMP /pub/chat/challenges/{challengeId}──→ ChatService Instance A
  └── 멤버 판정 (로컬 chat_room_members — ADR-0046)
        └── chat_messages 저장 + chat_outbox INSERT (한 트랜잭션)
              └── Outbox Worker → Kafka publish (chat.message.created, key = roomId)
                    └── 모든 인스턴스가 consume (인스턴스마다 다른 그룹)
                          ├── Instance A: /sub/chat/challenges/{challengeId} 구독자에게 전송
                          ├── Instance B: 구독자에게 전송
                          └── Instance C: 구독자 없음 → 무시
```

> 이 절은 2026-09-13까지 코드 블록이 열린 채 끊겨 있었다. 보완하면서 흐름을 채웠다.