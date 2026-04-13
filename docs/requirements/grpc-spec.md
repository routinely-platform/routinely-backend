# gRPC 명세 (gRPC Service Specification)

> 서비스 간 동기 통신은 gRPC를 사용한다.
> "지금 당장 결과가 필요한 요청"에만 사용하며, 이벤트성 통신은 Kafka를 사용한다. (ADR-0007)

---

## 기본 원칙

- **Command는 gRPC** — 즉시 결과가 필요한 내부 서비스 간 요청
- **단방향 의존** — 호출 방향은 항상 소비자 → 제공자. 순환 호출 금지
- **Proto 파일 위치** — `libs/proto/src/main/proto/` 에 중앙 관리

---

## 호출 관계 요약

```
ChatService ──────────────→ ChallengeService
                            (CheckMembership)

RoutineService ────────────→ ChallengeService
                            (GetChallengeContext)
```

| Caller | Server | RPC | 호출 시점 |
|--------|--------|-----|-----------|
| ChatService | ChallengeService | `CheckMembership` | 채팅방 입장 또는 메시지 발송 전 멤버 검증 |
| RoutineService | ChallengeService | `GetChallengeContext` | 챌린지 루틴 실행 완료 처리 전 유효성 검증 |

---

## Proto 정의

### `challenge_service.proto`

ChallengeService가 gRPC 서버로 동작한다.

```protobuf
syntax = "proto3";

package routinely.challenge.v1;

option java_multiple_files = true;
option java_package = "com.routinely.proto.challenge";
option java_outer_classname = "ChallengeServiceProto";

service ChallengeGrpcService {
  // ChatService → ChallengeService
  // 채팅방 입장 또는 메시지 발송 전 챌린지 멤버 여부 확인
  rpc CheckMembership(CheckMembershipRequest) returns (CheckMembershipResponse);

  // RoutineService → ChallengeService
  // 챌린지 루틴 실행 완료 처리 전 챌린지 유효성 및 멤버 상태 확인
  rpc GetChallengeContext(GetChallengeContextRequest) returns (GetChallengeContextResponse);
}

// ── CheckMembership ────────────────────────────────────────────────

message CheckMembershipRequest {
  int64 challenge_id = 1;
  int64 user_id      = 2;
}

message CheckMembershipResponse {
  bool   is_active_member = 1; // ACTIVE 상태 멤버 여부
  string member_role      = 2; // "LEADER" | "MEMBER" — is_active_member=false 이면 빈 문자열
}

// ── GetChallengeContext ────────────────────────────────────────────

message GetChallengeContextRequest {
  int64 routine_template_id = 1; // 실행하려는 루틴 템플릿 ID
  int64 user_id             = 2;
}

message GetChallengeContextResponse {
  bool   is_challenge_routine = 1; // 해당 루틴이 챌린지 소속 루틴인지
  int64  challenge_id         = 2; // is_challenge_routine=false 이면 0
  string challenge_status     = 3; // "WAITING" | "ACTIVE" | "ENDED"
  bool   is_member_active     = 4; // 해당 사용자가 ACTIVE 멤버인지
}
```

---

## RPC 상세

### `CheckMembership`

**호출자**: ChatService

**호출 시점**:
- 사용자가 챌린지 채팅방에 입장하려 할 때
- 사용자가 챌린지 채팅방에 메시지를 발송하려 할 때

**처리 흐름**:

```
ChatService (HTTP 요청 수신)
    └── ChallengeGrpcService.CheckMembership(challengeId, userId)
        └── is_active_member == true
            ├── true  → 채팅 처리 계속
            └── false → FORBIDDEN (NOT_CHALLENGE_MEMBER)
```

**응답 케이스**:

| 상황 | `is_active_member` | `member_role` |
|------|--------------------|---------------|
| ACTIVE 멤버 (일반) | true | `MEMBER` |
| ACTIVE 멤버 (리더) | true | `LEADER` |
| 탈퇴/추방 멤버 | false | `""` |
| 멤버 아님 | false | `""` |

---

### `GetChallengeContext`

**호출자**: RoutineService

**호출 시점**:
- 사용자가 챌린지 루틴 실행을 COMPLETED로 처리하려 할 때

**처리 흐름**:

```
RoutineService (루틴 실행 완료 요청 수신)
    └── ChallengeGrpcService.GetChallengeContext(routineTemplateId, userId)
        ├── is_challenge_routine == false → 개인 루틴으로 처리 (gRPC 불필요하나 확인 용도)
        └── is_challenge_routine == true
            ├── challenge_status != "ACTIVE" → FORBIDDEN (CHALLENGE_ALREADY_ENDED 등)
            ├── is_member_active == false    → FORBIDDEN (NOT_CHALLENGE_MEMBER)
            └── 모두 통과 → 실행 완료 처리
```

**응답 케이스**:

| 상황 | `is_challenge_routine` | `challenge_status` | `is_member_active` |
|------|------------------------|--------------------|--------------------|
| 개인 루틴 | false | `""` | false |
| 챌린지 루틴 / ACTIVE 챌린지 / ACTIVE 멤버 | true | `ACTIVE` | true |
| 챌린지 루틴 / 종료된 챌린지 | true | `ENDED` | - |
| 챌린지 루틴 / 탈퇴한 멤버 | true | `ACTIVE` | false |

---

## 오류 처리

gRPC 호출 실패 시 gRPC Status Code를 사용한다.

| 상황 | gRPC Status | 호출자 처리 |
|------|-------------|-------------|
| 정상 | `OK` | 응답 값으로 비즈니스 로직 처리 |
| 챌린지 없음 | `NOT_FOUND` | HTTP 404 반환 |
| 내부 오류 | `INTERNAL` | HTTP 500 반환 |
| 타임아웃 | `DEADLINE_EXCEEDED` | HTTP 503 또는 재시도 |

> 비즈니스 판단(멤버 아님, 챌린지 종료 등)은 gRPC Status가 아닌
> 응답 필드(`is_active_member`, `challenge_status`)로 전달한다.

---

## 포트 설정

| 서비스 | HTTP 포트 | gRPC 포트 |
|--------|-----------|-----------|
| user-service | 8081 | 9081 |
| routine-service | 8082 | 9082 |
| challenge-service | 8083 | 9083 |
| chat-service | 8084 | 9084 |
| notification-service | 8085 | 9085 |

gRPC 포트는 HTTP 포트 + 1000으로 통일한다.
