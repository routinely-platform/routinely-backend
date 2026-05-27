# ADR-0031: 챌린지 초대 기능 v2 이관 및 구현 방식

- **Status**: Accepted (MVP 제외 결정) / Proposed (v2 구현 방식)
- **Date**: 2026-05-27
- **Author**: Routinely Project

---

## 1. Context

챌린지 초대 기능은 비공개 챌린지에 특정 사용자를 초대하는 기능이다.
초기 설계에서는 챌린지 생성 화면과 챌린지 상세 화면에서 방장이 닉네임 검색으로 사용자를 초대할 수 있도록 검토되었다.

검토 결과 MVP 단계에서 다음 네 가지 이유로 인해 구현이 부적합하다고 판단했다.

1. **인프라 의존성 부재**: 이메일 발송 인프라가 MVP에 없다 (이메일 인증 자체가 v2). 소셜 로그인도 v2 예정이라 카카오톡 같은 외부 채널 SDK 연동도 v2다. 외부 공유와 이메일 초대 모두 MVP에서 실현 불가하다.

2. **인앱 초대도 MVP 범위로는 과함**: 신규 테이블 1개(`challenge_invitations`), 신규 API 4개(초대 발송 / 수락 / 거절 / 내 초대 목록), 신규 이벤트 2~3개, 사용자 닉네임 검색 + UI, 초대 알림 UI·검증 로직이 따라온다.

3. **MVP 사용자 시나리오와 거리 있음**: MVP 단계는 사용자 풀이 작아 초대 행위 자체가 드물다. 공개 챌린지 발견·참여가 주된 사용 흐름이며, 비공개 챌린지 + 멤버 초대는 저빈도 시나리오다.

4. **기존 v2 이관 결정들과의 일관성**: `notification_settings`, 챌린지 삭제, 멤버 강퇴, 챌린지 조기 종료가 이미 v2로 이관된 상태다. 초대 기능도 같은 결로 v2가 자연스럽다.

---

## 2. Decision

**챌린지 초대 기능은 MVP에서 제외하고 v2로 이관한다.**

다만 향후 v2 도입 시 마이그레이션 부담을 최소화하기 위해 일부 구조(컬럼·도메인 기반)는 MVP에 유지한다.

### MVP에서 유지하는 것

- **비공개 챌린지 생성 기능**: 방장이 `is_public = false`로 챌린지를 생성할 수 있다. 다만 다른 사용자가 들어올 경로가 MVP에 없으므로 사실상 방장 혼자 참여 가능한 상태다. 이는 "방장 혼자여도 챌린지 정상 시작" 정책(ADR-0030)과 자연스럽게 맞물린다.
- **`invite_code` 컬럼**: 비공개 챌린지 생성 시 자동 생성된다. MVP에서 사용 경로는 없으며, v2에서 링크 공유 기반 가입 용도로 활용 예정이다.
- **`is_public` 컬럼**: 공개/비공개 선택은 챌린지 생성 시 그대로 가능하다. 공개↔비공개 전환 정책도 기존 결정 유지.

### MVP에서 제거하는 것

**챌린지 생성 화면**
- 사용자 닉네임 검색 입력창
- 검색 결과 리스트 및 초대 버튼·초대 목록 영역
- 챌린지 생성 직후 "초대 링크 공유 모달"

**챌린지 상세 화면**
- 방장 ⋯ 메뉴의 "사용자 초대" 항목
- 방장 ⋯ 메뉴의 "초대 링크 공유" 항목
- (방장 ⋯ 메뉴는 v2에서 채워질 예정. MVP에서는 거의 비어 있어도 무방)

**알림 영역**
- 초대 알림 카드
- 수락/거절 액션
- "내 초대 목록" 화면

---

## 3. v2 구현 가이드

### 3-1. 핵심 비즈니스 규칙

| 항목 | 규칙 |
|---|---|
| 초대 가능 시점 | 챌린지 상태 `WAITING`인 동안만 |
| 초대 가능 주체 | 방장(`creator_user_id`)만 |
| 초대 대상 검색 | 닉네임 풀 매칭만 (부분 검색 X) — 프라이버시·스팸 어뷰징 방지, 닉네임 UNIQUE이므로 충분 |
| 수락 가능 시점 | 챌린지 `WAITING` + `max_members` 미달일 때만 |
| 수락 검증 | 수락 시점에 챌린지 상태·정원·중복 참여 여부 재검증 (TOCTOU 방지) |
| 만료 처리 | 별도 스케줄러 X. 수락 시점 검증만으로 처리. 운영 중 PENDING 적체 문제 시 분기 배치로 정리 |
| 외부 채널 | 인앱 알림(SSE) 전용. 이메일/카카오 미사용 |

### 3-2. 두 가지 초대 방식 병행

v2에서 두 방식이 공존하며 사용 맥락이 다르다.

```
방식 1 — 인앱 초대 (사용자 검색 기반)
  · invite_code 미사용
  · challenge_invitations 레코드 + JWT 검증
  · 사용 맥락: "내가 아는 사람을 콕 집어 초대"

방식 2 — 링크 공유 초대 (받는 사람을 모름)
  · invite_code 사용
  · https://routinely.app/challenges/join/{invite_code}
  · 카카오 공유 SDK 또는 단순 링크 복사
  · 사용 맥락: "모르는 사람도 누구나 참여"
```

### 3-3. 전체 초대 프로세스

**발송 흐름**

```
1. 방장이 챌린지 생성 화면 또는 챌린지 상세 ⋯ 메뉴에서 "사용자 초대" 진입
2. 닉네임 입력창에 초대할 사용자 풀 네임 입력
3. User 서비스 닉네임 풀 매칭 API 호출 (gRPC: UserService.FindByNicknameExact)
4. 일치하는 사용자 정보 표시 → 방장이 "초대" 클릭
5. Challenge 서비스: 초대 검증
   - 방장 권한 확인
   - 챌린지 status = WAITING 확인
   - 시작일 이전 확인
   - max_members 미달 확인
   - 중복 초대 방지 (challenge_id + invitee_user_id UNIQUE)
   - 이미 참여 중인 사용자 제외
6. challenge_invitations INSERT (status = PENDING)
7. ChallengeInvited 이벤트 Outbox INSERT (같은 트랜잭션)
8. 트랜잭션 커밋 → Outbox poller가 Kafka 발행
9. Notification 서비스 Consumer: notification_history INSERT + SSE 알림 발송
```

**수신·수락 흐름**

```
1. 받는 사람 인앱 알림 영역에 "OOO님이 챌린지에 초대했어요" 표시
2. 알림 클릭 → 챌린지 상세 또는 초대 카드 모달
3. 수락 또는 거절 클릭
4. Challenge 서비스: 수락 시점 재검증 (TOCTOU 방지)
   - invitation 존재 + PENDING 여부
   - 챌린지 status = WAITING 여부
   - max_members 미달 여부
   - 본인이 이미 참여 중인지
5. 검증 통과: challenge_members INSERT + invitation status = ACCEPTED
6. 검증 실패: "이미 시작된 챌린지입니다" / "정원이 가득 찼습니다" / "이미 참여 중인 챌린지입니다"
```

**거절 흐름**

```
1. 받는 사람이 "거절" 클릭
2. invitation status = REJECTED, responded_at = now()
3. InvitationRejected 이벤트 발행 (선택, 방장 알림용)
```

### 3-4. 챌린지 생성 시 초대 vs 생성 후 초대

두 경우 모두 API와 도메인 로직이 동일하다. 화면만 다르다.

| 검증 항목 | 챌린지 생성 시 | 생성 후 (대기) |
|---|---|---|
| 방장 권한 | ✅ | ✅ |
| 챌린지 상태 WAITING | (생성과 함께) | ✅ |
| 시작일 이전 | ✅ | ✅ |
| max_members 미달 | ✅ | ✅ |
| 중복 초대 방지 | ✅ | ✅ |
| 이미 참여한 멤버 초대 불가 | ✅ | ✅ |

진행중(ACTIVE) 또는 종료(ENDED) 상태에서는 초대 자체가 불가능하다.

### 3-5. UX 결정 — 비공개 챌린지에서 초대코드 입력창 없음

방장이 명시적으로 초대한 사용자가 알림에서 수락 클릭하는 흐름이면 이미 검증된 경로다. invitation 레코드 + JWT 인증 조합으로 보안이 확보되므로 초대코드 입력은 중복 검증이고 UX만 저해한다.

초대코드 입력은 인앱 초대가 아닌 **링크 공유 기반 가입**에서만 사용한다.

---

## 4. v2 구현 명세

### 4-1. 신규 테이블

```sql
challenge_invitations
- id              BIGINT PK
- challenge_id    BIGINT NOT NULL
- invitee_user_id BIGINT NOT NULL  -- 초대받은 사용자
- inviter_user_id BIGINT NOT NULL  -- 초대한 방장
- status          VARCHAR(20)      -- PENDING / ACCEPTED / REJECTED / EXPIRED
- created_at      TIMESTAMPTZ NOT NULL
- responded_at    TIMESTAMPTZ NULL

CONSTRAINT uq_invitation_challenge_invitee UNIQUE (challenge_id, invitee_user_id)
CONSTRAINT ck_invitation_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'EXPIRED'))
```

### 4-2. 신규 API

```
POST   /api/v1/challenges/{id}/invitations   — 방장이 초대 발송 (body: inviteeUserId)
POST   /api/v1/invitations/{id}/accept       — 수락
POST   /api/v1/invitations/{id}/reject       — 거절
GET    /api/v1/me/invitations?status=PENDING — 내 초대 목록
```

### 4-3. 신규 이벤트

| 이벤트 | 발생 시점 | 필수 여부 |
|---|---|---|
| `ChallengeInvited` | 초대 발송 시 | 필수 (Notification 트리거) |
| `InvitationAccepted` | 수락 시 | 선택 (방장 알림용) |
| `InvitationRejected` | 거절 시 | 선택 (방장 알림용) |

### 4-4. User 서비스 gRPC 확장

```
UserService.FindByNicknameExact(nickname: string) → UserInfo
```

닉네임 풀 매칭만 (부분 검색 X).

### 4-5. `invite_code`의 v2 활용

MVP에서 자동 생성·저장만 하고 사용하지 않는 `invite_code`는 v2에서 링크 공유 기반 참여에 활용한다.

```
https://routinely.app/challenges/join/{invite_code}
→ 받는 사람이 로그인 후 코드 검증 → 챌린지 참여
```

---

## 5. Consequences

**긍정적 결과**
- MVP 범위가 단순해진다. 초대 관련 4개 API, 테이블, 이벤트, UI를 MVP에서 제거한다.
- v2 시점에 인앱 초대(닉네임 검색)와 링크 공유 초대 두 방식을 병행 도입할 수 있다.
- `invite_code` 컬럼을 MVP에서 유지함으로써 v2 마이그레이션 비용이 없다.

**부정적 결과·트레이드오프**
- 비공개 챌린지를 생성해도 MVP에서는 방장 혼자만 참여 가능하다. 비공개 챌린지의 실질적 가치가 v2 전까지 제한된다.
- `invite_code`가 생성·저장되나 MVP에서 사용 경로가 없어 사용하지 않는 컬럼이 잠시 존재한다.

---

## 6. Related

- ADR-0005: 챌린지 그룹 도메인 설계
- ADR-0029: 챌린지 상태 자동 전이 전략
- ADR-0030: 방장 혼자 챌린지 시작 정책
- v2 이관 결정들: `notification_settings` v2 도입 (product-planning.md), 챌린지 삭제 v2, 멤버 강퇴 v2, 챌린지 조기 종료 v2
