# ADR-0046: 채팅 멤버 판정과 방 주소 — 로컬 멤버 표 · 챌린지 ID

## Status

Accepted (2026-09-13)

**대체** — `grpc-spec.md`의 "ChatService → ChallengeService `CheckMembership`(채팅방 입장·메시지 발송 전 멤버 검증)"

---

## 1. Context

chat-service는 아직 껍데기다. 착수 전에 문서끼리 어긋난 두 가지를 정리한다.

### 1.1 멤버 판정이 두 방식으로 적혀 있었다

- `grpc-spec.md` — 채팅방 입장·메시지 발송마다 challenge-service에 **gRPC `CheckMembership`** 을 호출한다
- `chat_room_members`(V1) — chat-service가 **자기 멤버 표**를 갖는다. `role` · `is_active` · `joined_at` · `left_at` · `last_read_message_id`
- `docs/product/policies.md` §7 — 재참여하면 `joined_at` 이후 대화만 보인다 · 방장이 바뀌면 `OWNER`도 따라 바뀐다(`newLeaderUserId`)

### 1.2 클라이언트가 방 번호를 알 방법이 없었다

api-spec의 채팅 API는 전부 **`roomId`** 로 되어 있다(`/chat/rooms/{roomId}/messages`, STOMP `/pub/chat/{roomId}`).
그런데 사용자는 **챌린지 상세**에서 채팅을 연다 — 화면이 아는 것은 `challengeId`뿐이다.
방은 챌린지 시작 시 **chat-service가 비동기로** 만들어 challenge-service는 방 번호를 모르고, 방 목록 화면도 없다(FE #28).

---

## 2. Decision

1. **멤버 판정은 chat-service의 `chat_room_members`로 한다.** 챌린지 서비스에 gRPC로 묻지 않는다
2. 표는 **이벤트로 동기화**한다 — `challenge.started`(방 생성 · 멤버 채움 · `OWNER`) · `challenge.member.left`(비활성 · `left_at` · 승계). `challenge.member.joined`는 v2(#118)
3. 탈퇴 이벤트를 처리하면 **그 사용자의 채팅 구독을 끊는다** — 이벤트 지연 창을 좁힌다
4. **방 주소는 챌린지 ID다** — REST `/api/v1/chat/challenges/{challengeId}/…`, STOMP `/pub`·`/sub/chat/challenges/{challengeId}`
5. 시작 전이라 방이 없으면 **`CHAT_ROOM_NOT_OPENED`** 로 답한다. **채팅방 목록 API는 두지 않는다**

---

## 3. Rationale

### 3.1 gRPC를 더해도 멤버 표는 없어지지 않는다

| | gRPC로 묻기 | 로컬 표 |
|---|---|---|
| 호출 | 입장·발송마다 — 20명 방에 하루 메시지 200개면 **200번** | 0번 |
| challenge-service 장애 | **채팅 전체가 멈춘다** | 채팅은 계속된다 |
| 멤버 표 | **여전히 필요** — `joined_at` · `OWNER` · 읽음 위치 | 필요 |

재참여 이전 대화 차단, 방장 표시, 안 읽은 수가 전부 그 표에 있다. gRPC를 더하면 **멤버 정보의 출처가 둘**이 될 뿐이다.

### 3.2 챌린지당 방은 하나다

`uq_cr_challenge_id`로 챌린지와 방이 1:1이다. 챌린지 ID를 주소로 쓰면 **방 번호를 찾는 단계가 사라진다.**
`roomId`는 내부 식별자로 남긴다(`chat.message.created` 파티션 키 등).

---

## 4. 기각한 대안

| | 방식 | 기각 이유 |
|---|---|---|
| A | 메시지마다 gRPC `CheckMembership` | 3.1 — 호출 폭증 · 장애 결합, 표는 그대로 필요 |
| B | 구독(입장) 시에만 gRPC, 발송은 로컬 표 | 동기 의존을 남기면서 얻는 것은 입장 순간의 정확성뿐. 이미 구독한 사람의 탈퇴는 어차피 이벤트로 끊어야 한다 |
| C | 챌린지 상세 응답에 `roomId`를 싣는다 | 방은 chat-service가 나중에 만든다 — challenge-service는 번호를 모른다 |
| D | 방 목록을 받아 `challengeId`로 찾는다 | 목록 화면이 없는데 채팅을 열 때마다 전체 목록을 받는다 |

---

## 5. 트레이드오프

- **탈퇴 직후 수 초** 동안 나간 사람이 메시지를 보낼 수 있다 — 탈퇴 이벤트 처리 시 구독을 끊어 창을 좁힌다.
  멤버십이 결제·권한처럼 한 순간도 어긋나면 안 되는 도메인이면 A를 택했을 것이다
- 챌린지 시작 직후 `challenge.started`가 처리되기 전 **수 초** 동안은 방이 없다 — `CHAT_ROOM_NOT_OPENED`로 답하고 화면이 잠시 뒤 다시 시도한다

---

## 6. 구현 노트

- 멤버 판정 — `(chat_room_id, user_id)` UNIQUE가 이미 있다. `is_active = true`가 아니면 403 `CHAT_NOT_MEMBER`
- **구독 끊기는 모든 인스턴스에서** 일어나야 한다 — `challenge.member.left`는 컨슈머 그룹 하나라 한 인스턴스만 받는다.
  탈퇴 SYSTEM 메시지를 `chat.message.created`로 브로드캐스트하고, 받은 인스턴스가 그 사용자의 구독을 정리한다
- `OWNER` 초기값 — `challenge.started` 페이로드의 **`leaderUserId`**(#167)
- STOMP 인증 — `CONNECT` 프레임 헤더(ADR-0021 "인증")
- api-spec 채팅 경로 변경은 **#163**에서 한다

---

## 7. Related ADRs

- ADR-0010 채팅 스토리지 · ADR-0016 채팅 브로드캐스트 · ADR-0021 실시간 전송(인증)
- ADR-0042 챌린지 멤버십 생애주기(`newLeaderUserId`) · ADR-0044 챌린지 루틴 정의(`challenge.started` 페이로드)
