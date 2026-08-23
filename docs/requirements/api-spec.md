# API 명세

> 모든 요청은 `Spring Cloud Gateway`를 통해 라우팅된다.
> 인증이 필요한 API는 `Authorization: Bearer {accessToken}` 헤더 필수.

---

## 공통 규약

### 공통 응답 DTO

모든 응답은 아래 단일 클래스로 통일한다.
`@JsonInclude(NON_NULL)` 적용으로 null 필드는 JSON 출력에서 생략된다.

```java
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@RequiredArgsConstructor
public class ApiResponse<T> {
    private final boolean success;
    private final String message;
    private final T data;           // 성공 시 존재, 실패 시 null → 생략
    private final String errorCode; // 실패 시 존재, 성공 시 null → 생략

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data, null);
    }

    public static <T> ApiResponse<T> ok(String message) {
        return new ApiResponse<>(true, message, null, null);
    }

    public static <T> ApiResponse<T> fail(String errorCode, String message) {
        return new ApiResponse<>(false, message, null, errorCode);
    }
}
```

**성공 (data 있음)**
```json
{
  "success": true,
  "message": "챌린지 생성에 성공했습니다.",
  "data": { ... }
}
```

**성공 (data 없음)**
```json
{
  "success": true,
  "message": "탈퇴가 완료되었습니다."
}
```

**실패**
```json
{
  "success": false,
  "message": "챌린지를 찾을 수 없습니다.",
  "errorCode": "CHALLENGE_NOT_FOUND"
}
```

---

### HTTP 상태코드

| 코드 | 설명 |
|---|---|
| 200 | 성공 (조회 / 수정 / 삭제) |
| 201 | 생성 성공 |
| 400 | 잘못된 요청 (유효성 검사 실패) |
| 401 | 인증 실패 (토큰 없음 / 만료) |
| 403 | 권한 없음 |
| 404 | 리소스 없음 |
| 409 | 중복 / 충돌 |

---

## Gateway 라우팅

| 경로 패턴 | 라우팅 대상 |
|---|---|
| `/api/v1/auth/**` | user-service |
| `/api/v1/users/**` | user-service |
| `/api/v1/challenges/**` | challenge-service |
| `/api/v1/categories/**` | routine-service |
| `/api/v1/routine-templates/**` | routine-service |
| `/api/v1/routines/**` | routine-service |
| `/api/v1/routine-executions/**` | routine-service |
| `/api/v1/feed/**` | routine-service |
| `/api/v1/statistics/**` | routine-service |
| `/api/v1/chat/**` | chat-service |
| `/ws/chat/**` | chat-service (WebSocket) |
| `/api/v1/notifications/**` | notification-service |

---

## 1. 인증 / 사용자 (User Service)

### 1-1. 인증

#### `POST /api/v1/auth/signup` — 회원가입
- Auth: ❌

**Request**
```json
{
  "email": "user@example.com",
  "password": "password123!",
  "nickname": "김루틴"
}
```

**Response** `201`
```json
{
  "success": true,
  "message": "회원가입에 성공했습니다.",
  "data": {
    "userId": 1,
    "email": "user@example.com",
    "nickname": "김루틴"
  }
}
```

---

#### `POST /api/v1/auth/login` — 로그인
- Auth: ❌

**Request**
```json
{
  "email": "user@example.com",
  "password": "password123!"
}
```

**Response** `200`
```json
{
  "success": true,
  "message": "로그인에 성공했습니다.",
  "data": {
    "accessToken": "eyJhbGci..."
  }
}
```
> Refresh Token은 응답 바디에 포함되지 않는다.  
> `Set-Cookie: refresh_token=<UUID>; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth` 헤더로 전달된다.  
> Refresh Token은 opaque token(UUID)으로 Redis에 저장된다.

---

#### `POST /api/v1/auth/refresh` — 토큰 갱신
- Auth: ❌

**Request**
> 요청 바디 없음. Refresh Token은 HttpOnly 쿠키(`refresh_token`)로 자동 전송된다.

**Response** `200`
```json
{
  "success": true,
  "message": "토큰이 갱신되었습니다.",
  "data": {
    "accessToken": "eyJhbGci..."
  }
}
```

---

#### `POST /api/v1/auth/logout` — 로그아웃
- Auth: ✅

**Response** `200`
```json
{
  "success": true,
  "message": "로그아웃되었습니다."
}
```

---

### 1-2. 사용자

#### `GET /api/v1/users/me` — 내 프로필 조회
- Auth: ✅

**Response** `200`
```json
{
  "success": true,
  "message": "사용자 정보가 조회되었습니다.",
  "data": {
    "userId": 1,
    "email": "user@example.com",
    "nickname": "김루틴",
    "bio": null,
    "profileImageUrl": null,
    "createdAt": "2024-03-15T10:00:00Z",
    "nicknameChangeableAt": null
  }
}
```

> `bio`가 null이면 클라이언트에서 한 줄 소개 영역을 미표시한다.  
> `createdAt` 기반 "함께한 지 N일째" 계산은 클라이언트에서 수행한다.  
> `nicknameChangeableAt`이 null이면 즉시 변경 가능, non-null이면 해당 시각 이후 변경 가능 (`nicknameUpdatedAt + 30일` 계산값).

---

#### `PATCH /api/v1/users/me` — 프로필 수정 (닉네임 + 한 줄 소개)
- Auth: ✅
- Content-Type: `application/json`

**Request**
```json
{
  "nickname": "새닉네임",
  "bio": "매일 조금씩 성장하는 중"
}
```

> `bio`를 빈 문자열 또는 null로 전송하면 소개가 삭제된다.  
> 닉네임 변경 후 30일 이내 재변경 시 `NICKNAME_CHANGE_COOLDOWN_ACTIVE` (409) 응답.  
> 쿨다운 기간은 `NICKNAME_COOLDOWN_DAYS` 환경변수로 조정 가능 (기본: 30일).

**Response** `200`
```json
{
  "success": true,
  "message": "사용자 정보 변경이 완료되었습니다.",
  "data": {
    "userId": 1,
    "email": "user@example.com",
    "nickname": "새닉네임",
    "bio": "매일 조금씩 성장하는 중",
    "profileImageUrl": null,
    "createdAt": "2024-03-15T10:00:00Z",
    "nicknameChangeableAt": "2024-04-14T10:00:00Z"
  }
}
```

**Response** `409` — 쿨다운 미경과
```json
{
  "success": false,
  "message": "닉네임은 변경 후 30일이 지나야 다시 변경할 수 있습니다. (남은 기간: 15일)",
  "data": null
}
```

---

#### `PUT /api/v1/users/me/profile-image` — 프로필 이미지 생성/교체
- Auth: ✅
- Content-Type: `multipart/form-data`
- 구현 이슈: #95

**구현 메모**
- 이미지 저장소의 객체 삭제/교체를 위해 `users.profile_image_object_key VARCHAR(500) NULL` 컬럼을 추가한다.
- `profileImageUrl`은 클라이언트 표시용 URL이며, `profileImageObjectKey`는 S3 등 object storage 내부 객체 식별자로 사용한다.
- 저장 key 규칙: `profile-images/{yyyy}/{MM}/{uuid}.{ext}` (매 업로드마다 새 key 발급 → 이전 객체는 트랜잭션 커밋 이후 best-effort 삭제)
- MIME 타입과 파일 시그니처를 함께 검증한다.
- 응답 바디는 `GET`/`PATCH /me` 와 동일한 전체 프로필(`ProfileResponse`)을 반환한다.

**Request**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `image` | file | ✅ | `image/jpeg`, `image/png`, `image/webp` (최대 5MB) |

**Response** `200`
```json
{
  "success": true,
  "message": "프로필 이미지가 변경되었습니다.",
  "data": {
    "userId": 1,
    "email": "user@example.com",
    "nickname": "새닉네임",
    "bio": "매일 조금씩 성장하는 중",
    "profileImageUrl": "https://cdn.example.com/profile-images/2026/07/uuid.jpg",
    "createdAt": "2024-03-15T10:00:00Z",
    "nicknameChangeableAt": "2024-04-14T10:00:00Z"
  }
}
```

---

#### `DELETE /api/v1/users/me/profile-image` — 프로필 이미지 삭제
- Auth: ✅
- 구현 이슈: #95

삭제 시 DB의 `profile_image_url`, `profile_image_object_key`를 함께 `NULL`로 변경하고, 트랜잭션 커밋 이후 storage 객체를 best-effort 삭제한다. 이미지가 없으면 no-op. 응답은 전체 프로필(`ProfileResponse`)을 반환한다.

**Response** `200`
```json
{
  "success": true,
  "message": "프로필 이미지가 삭제되었습니다.",
  "data": {
    "userId": 1,
    "email": "user@example.com",
    "nickname": "새닉네임",
    "bio": "매일 조금씩 성장하는 중",
    "profileImageUrl": null,
    "createdAt": "2024-03-15T10:00:00Z",
    "nicknameChangeableAt": "2024-04-14T10:00:00Z"
  }
}
```

---

#### `DELETE /api/v1/users/me` — 회원 탈퇴 [v2]
- Auth: ✅
- `is_active = false` 소프트 딜리트, 이메일/닉네임 재사용 불가

**Response** `200`
```json
{
  "success": true,
  "message": "회원 탈퇴가 완료되었습니다."
}
```

---

## 2. 챌린지 (Challenge Service)

### 2-1. 챌린지 CRUD

#### `POST /api/v1/challenges` — 챌린지 생성
- Auth: ✅
- MVP 값 제약 (#160)

| 필드 | 제약 |
|---|---|
| `isPublic` | **`false`면 `400`.** MVP는 공개 챌린지만 만들 수 있다 (필드는 v2를 위해 남겨 둔다) |
| `maxMembers` | **2 ~ 20** |
| `startedAt` · `endedAt` | 시작일은 오늘 이후, 종료일 ≥ 시작일, **기간 최대 100일** |

> 기간은 **시작일과 종료일을 모두 포함해** 센다 — `2025-02-01 ~ 2025-05-11`이 정확히 100일이다.
> `challenge_member_summary.total_scheduled = ended_at - started_at + 1`과 같은 기준이다.

**Request**
```json
{
  "title": "30일 아침 운동 챌린지",
  "description": "매일 아침 30분 운동하기",
  "categoryCode": "EXERCISE",
  "isPublic": true,
  "maxMembers": 10,
  "startedAt": "2025-02-01",
  "endedAt": "2025-03-02",
  "routineTitle": "아침 운동 30분",
  "scheduleType": "DAILY",
  "targetCount": null
}
```

> 루틴 정보(`routineTitle`, `scheduleType`, `targetCount`)도 함께 입력한다. challenge-service는 저장하지 않고 `challenge.created` 이벤트로 전달한다 (#132).
> **챌린지는 요일 지정형(`SPECIFIC_DAYS`)을 쓸 수 없다** — `scheduleType`: `DAILY`(매일) \| `WEEKLY_COUNT`(주 N회) \| `MONTHLY_COUNT`(월 N회). `targetCount`는 `WEEKLY_COUNT`/`MONTHLY_COUNT`일 때만 1 이상으로 필수이며, `DAILY`에서는 `null`이다. 멤버 생활 패턴이 달라 특정 요일 강제가 부적절하기 때문 (ADR-0035, ADR-0039).
> 선호 수행 시각은 챌린지 생성 시 입력받지 않는다. 챌린지 루틴은 모든 멤버에게 동일하지만(ADR-0026), 알림 시각은 개인 일과에 종속되므로 멤버 각자가 본인 `routines` 인스턴스에서 설정한다 (ADR-0035).

**Response** `201`
```json
{
  "success": true,
  "message": "챌린지가 생성되었습니다.",
  "data": {
    "challengeId": 1,
    "title": "30일 아침 운동 챌린지",
    "description": "매일 아침 운동하기",
    "categoryCode": "EXERCISE",
    "isPublic": true,
    "inviteCode": null,
    "maxMembers": 10,
    "status": "WAITING",
    "startedAt": "2025-02-01",
    "endedAt": "2025-03-02",
    "createdAt": "2025-01-15T10:00:00Z"
  }
}
```

---

#### `GET /api/v1/challenges` — 공개 챌린지 목록 조회
- Auth: ✅
- Query: `page`, `size`

> MVP: `status`는 `WAITING`으로 고정되며 쿼리 파라미터로 변경할 수 없다. 진행 중인 챌린지(ACTIVE)는 목록에 노출되지 않는다.
> `keyword`, `categoryCode`, 정렬 옵션은 #120에서 Querydsl 동적 쿼리와 함께 구현한다.
> v2에서 리더가 `joinableUntilPercent`를 설정하면 ACTIVE 상태 챌린지도 조건부 노출된다 (`product-planning.md` 9.3.2 참고).

**Response** `200`
```json
{
  "success": true,
  "message": "챌린지 목록이 조회되었습니다.",
  "data": {
    "content": [
      {
        "challengeId": 1,
        "title": "30일 아침 운동 챌린지",
        "description": "매일 아침 운동하기",
        "categoryCode": "EXERCISE",
        "status": "WAITING",
        "currentMembers": 5,
        "maxMembers": 10,
        "startedAt": "2025-02-01",
        "endedAt": "2025-03-02"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 50,
    "totalPages": 3
  }
}
```

---

#### `GET /api/v1/challenges/me` — 내 챌린지 목록
- Auth: ✅
- Query: `page`, `size`

**Response** `200` — 위와 동일한 페이징 구조
<!-- TODO: 후속 이슈 - status 필터, myStatus(ACTIVE/LEFT) 필드 추가 필요 -->

---

#### `GET /api/v1/challenges/{challengeId}` — 챌린지 상세 조회
- Auth: ✅

**Response** `200`
```json
{
  "success": true,
  "message": "챌린지 상세 조회가 완료되었습니다.",
  "data": {
    "challengeId": 1,
    "title": "30일 아침 운동 챌린지",
    "description": "매일 아침 운동하기",
    "categoryCode": "EXERCISE",
    "isPublic": true,
    "inviteCode": null,
    "maxMembers": 10,
    "currentMembers": 5,
    "status": "ACTIVE",
    "startedAt": "2025-02-01",
    "endedAt": "2025-03-02",
    "creatorUserId": 1,
    "myRole": "LEADER"
  }
}
```

---

#### `PATCH /api/v1/challenges/{challengeId}` — 챌린지 수정
- Auth: ✅ (LEADER만)
- 수정 정책은 필드 유형과 활성 멤버 수에 따라 달라진다.
- 상세 정책: `docs/requirements/challenge-update-policy.md`

**isPublic 수정 정책**

**MVP에서는 `isPublic: false` 요청 자체를 `400`으로 거부한다 (#160).** 생성만 막으면 "공개로 만든 뒤
비공개로 전환"으로 우회되기 때문에 `PATCH`도 함께 막는다. 아래 표는 **v2에서 비공개를 열었을 때** 적용된다.

| 상태 | 멤버 수 | 비공개→공개 | 공개→비공개 |
|---|---|:---:|:---:|
| WAITING / ACTIVE | 1명 (방장만) | ✅ | ⛔ MVP 차단 (v2: ✅ 초대코드 자동생성) |
| WAITING / ACTIVE | 2명 이상 | ✅ | ❌ |
| ENDED | 무관 | ❌ | ❌ |

- 비공개→공개 전환은 MVP에서도 허용한다 (이미 만들어진 비공개 챌린지를 여는 방향).
- 비공개→공개 전환 시 기존 `inviteCode` 유지 (다시 비공개 전환 시 재사용).
- 공개→비공개 전환 시 `inviteCode`가 없으면 자동 생성.

**그 외 필드 수정 정책** (`title`, `description`, `maxMembers`, `startedAt`, `endedAt`)
- `WAITING` 상태인 챌린지만 수정 가능하다.
  - **방장 혼자 (1명)**: 위 필드 모두 수정 가능.
  - **멤버 2명 이상**: `description`과 `maxMembers` 증가만 허용. 나머지 변경 시 `400` 반환.
  - **항상 변경 불가**: `categoryCode`, `scheduleType`, `targetCount`, `creatorUserId`.
- 날짜 제약:
  - `startedAt`은 오늘 이후로만 변경 가능하며, 반드시 `endedAt`보다 빨라야 한다.
  - `endedAt`은 `startedAt` 이후로만 변경 가능하다.
  - **최종 기간은 시작일 포함 100일 이하여야 한다.** 한쪽 날짜만 보내면 나머지는 저장된 값을 쓰므로,
    `endedAt`만 보낸 연장 요청도 병합 후 기간으로 판정한다 (#160).
- `maxMembers`는 **20 이하**여야 한다 (#160).

**Request**
```json
{
  "title": "수정된 제목",
  "description": "수정된 설명",
  "maxMembers": 15
}
```

**Response** `200`
```json
{
  "success": true,
  "message": "챌린지 정보가 수정되었습니다.",
  "data": {
    "challengeId": 1,
    "title": "수정된 제목",
    "description": "수정된 설명",
    "maxMembers": 15
  }
}
```

---

#### `PUT /api/v1/challenges/{challengeId}/image` — 챌린지 대표 이미지 생성/교체
- Auth: ✅ (LEADER만)
- `multipart/form-data`, 파트명 `image`
- 제약: MIME `image/jpeg`·`image/png`·`image/webp`, 최대 5MB, 매직넘버 시그니처 검증
- 저장 key 규칙: `challenge-images/{yyyy}/{MM}/{uuid}.{ext}` (매 업로드마다 새 key 발급 → 이전 객체는 트랜잭션 커밋 이후 best-effort 삭제)
- 응답은 갱신된 챌린지 상세(`imageUrl` 포함)
- 구현 이슈: #142

**Response** `200`
```json
{
  "success": true,
  "message": "챌린지 대표 이미지가 변경되었습니다.",
  "data": {
    "challengeId": 1,
    "title": "매일 30분 독서",
    "description": "함께 책 읽어요",
    "categoryCode": "READING",
    "isPublic": true,
    "inviteCode": null,
    "maxMembers": 10,
    "currentMembers": 3,
    "status": "WAITING",
    "startedAt": "2026-07-20",
    "endedAt": "2026-08-20",
    "creatorUserId": 42,
    "imageUrl": "https://cdn.example.com/challenge-images/2026/07/uuid.jpg",
    "myRole": "LEADER"
  }
}
```

**Error**
- `403 NOT_CHALLENGE_MEMBER` — 챌린지 멤버가 아님
- `403 FORBIDDEN` — 방장이 아님
- `400 EMPTY_FILE` / `400 UNSUPPORTED_IMAGE_TYPE` / `400 FILE_SIZE_EXCEEDED`

---

#### `DELETE /api/v1/challenges/{challengeId}/image` — 챌린지 대표 이미지 삭제
- Auth: ✅ (LEADER만)
- DB의 `image_url`/`image_object_key`를 NULL로 변경하고, 트랜잭션 커밋 이후 storage 객체를 best-effort 삭제한다.
- 응답은 갱신된 챌린지 상세(`imageUrl`은 `null`)
- 구현 이슈: #142

**Response** `200`
```json
{
  "success": true,
  "message": "챌린지 대표 이미지가 삭제되었습니다.",
  "data": {
    "challengeId": 1,
    "imageUrl": null,
    "myRole": "LEADER"
  }
}
```

---

<!-- POST-MVP: DELETE /api/v1/challenges/{challengeId} — 챌린지 삭제 기능은 MVP 범위에서 제외. 추후 구현 시 LEADER만 가능, 활성 멤버 존재 시 제한 여부 정책 결정 필요 -->

#### `POST /api/v1/challenges/{challengeId}/end` — 챌린지 종료 (V2 예정)
- Auth: ✅ (LEADER만)

> MVP 범위에서는 구현하지 않는다. V2에서 상태 전이 정책과 함께 구현한다.

**Response** `200`
```json
{
  "success": true,
  "message": "챌린지가 종료되었습니다.",
  "data": {
    "challengeId": 1,
    "status": "ENDED"
  }
}
```

---

### 2-2. 챌린지 멤버

#### `POST /api/v1/challenges/{challengeId}/members` — 공개 챌린지 참여 (재참여 포함)
- Auth: ✅
- 이전에 탈퇴한 공개 대기 챌린지도 재참여 가능 (정원·상태·강퇴 여부 검증)
- 재참여 시 이전 수행 기록은 유지되며 기존 멤버십 행의 상태와 참여 시각을 갱신

**Response** `201`
```json
{
  "success": true,
  "message": "챌린지에 참여되었습니다.",
  "data": {
    "challengeMemberId": 10,
    "challengeId": 1,
    "userId": 2,
    "role": "MEMBER",
    "status": "ACTIVE",
    "joinedAt": "2025-02-01T09:00:00Z"
  }
}
```

---

#### `POST /api/v1/challenges/join` — 초대 코드로 참여 (V2 예정)
- Auth: ✅

> MVP 범위에서는 구현하지 않는다. `invite_code`는 비공개 챌린지 생성 시 자동 발급되나 MVP에서 참여 경로가 없다. V2에서 링크 공유 기반 초대(`https://routinely.app/challenges/join/{invite_code}`)로 활용한다 (ADR-0031).

**Request**
```json
{
  "inviteCode": "ABC123"
}
```

**Response** `201` — 위와 동일

---

#### `POST /api/v1/challenges/{challengeId}/members/me/leave` — 챌린지 탈퇴
- Auth: ✅

**Response** `200`
```json
{
  "success": true,
  "message": "챌린지에서 탈퇴되었습니다."
}
```

---

<!-- V2: DELETE /api/v1/challenges/{challengeId}/members/{userId} — 멤버 강제 퇴장(kick)은 ADR-0031 기준 후속 범위에서 구현 -->

#### `GET /api/v1/challenges/{challengeId}/members` — 멤버 목록 조회
- Auth: ✅

**Response** `200`
```json
{
  "success": true,
  "message": "멤버 목록이 조회되었습니다.",
  "data": [
    {
      "userId": 1,
      "nickname": "김루틴",
      "role": "LEADER",
      "status": "ACTIVE",
      "joinedAt": "2025-01-15T10:00:00Z"
    }
  ]
}
```

---

#### `GET /api/v1/challenges/{challengeId}/ranking` — 챌린지 내 랭킹
- Auth: ✅

**Response** `200`
```json
{
  "success": true,
  "message": "랭킹 조회가 완료되었습니다.",
  "data": [
    {
      "rank": 1,
      "userId": 3,
      "nickname": "박열심",
      "acceptedCount": 28,
      "totalScheduled": 30,
      "achievementRate": 93.33,
      "lastCompletedAt": "2025-02-28T07:30:00Z"
    },
    {
      "rank": 2,
      "userId": 1,
      "nickname": "김루틴",
      "acceptedCount": 25,
      "totalScheduled": 30,
      "achievementRate": 83.33,
      "lastCompletedAt": "2025-02-27T08:00:00Z"
    }
  ]
}
```

---

## 3. 루틴 (Routine Service)

### 3-0. 카테고리

#### `GET /api/v1/categories` — 카테고리 목록 조회
- Auth: ✅
- 챌린지/루틴 생성 화면 카테고리 드롭다운에서 사용한다.

**Response** `200`
```json
{
  "success": true,
  "message": "카테고리 목록이 조회되었습니다.",
  "data": [
    { "code": "EXERCISE",     "name": "운동",        "icon": "🏃", "displayOrder": 1 },
    { "code": "READING",      "name": "독서",        "icon": "📚", "displayOrder": 2 },
    { "code": "STUDY",        "name": "공부/학습",   "icon": "📖", "displayOrder": 3 },
    { "code": "LANGUAGE",     "name": "어학",        "icon": "🗣️", "displayOrder": 4 },
    { "code": "HEALTH",       "name": "건강",        "icon": "💊", "displayOrder": 5 },
    { "code": "SLEEP",        "name": "수면",        "icon": "😴", "displayOrder": 6 },
    { "code": "DIET",         "name": "식습관",      "icon": "🥗", "displayOrder": 7 },
    { "code": "MEDITATION",   "name": "명상/마음챙김","icon": "🧘", "displayOrder": 8 },
    { "code": "SELF_IMPROVE", "name": "자기계발",    "icon": "🌱", "displayOrder": 9 },
    { "code": "PRODUCTIVITY", "name": "생산성",      "icon": "⚡", "displayOrder": 10 },
    { "code": "HOBBY",        "name": "취미",        "icon": "🎨", "displayOrder": 11 },
    { "code": "QUIT_HABIT",   "name": "금연/금주",   "icon": "🚭", "displayOrder": 12 }
  ]
}
```

---

### 3-1. 루틴 템플릿

#### `POST /api/v1/routine-templates` — 루틴 템플릿 생성
- Auth: ✅

**Request**
```json
{
  "title": "아침 달리기",
  "categoryCode": "EXERCISE",
  "scheduleType": "SPECIFIC_DAYS",
  "daysOfWeek": ["MON", "WED", "FRI"],
  "targetCount": null
}
```

> 개인 루틴 생성 시 `challengeId` 없이 요청한다.  
> 챌린지 루틴은 `challenge.created` 이벤트를 받은 routine-service가 내부적으로 생성한다 (클라이언트 직접 호출 아님).  
> 선호 수행 시각(`preferredTime`)·선호 요일(`preferredDays`)은 템플릿(정의)이 아니라 루틴 인스턴스 속성이다. 시각은 `POST /api/v1/routines` 또는 `PATCH /api/v1/routines/{routineId}`에서, 선호 요일은 `PATCH`에서 설정한다 (ADR-0035, ADR-0039).  
> **`scheduleType` (ADR-0039)**:  
> - `DAILY`(매일) — `daysOfWeek`/`targetCount` 모두 생략  
> - `SPECIFIC_DAYS`(요일 지정·강제) — `daysOfWeek`(MON~SUN 배열) 필수, `targetCount` 생략. 지정 요일에 수행하지 않으면 결석(MISSED)  
> - `WEEKLY_COUNT`(주 N회) / `MONTHLY_COUNT`(월 N회) — `targetCount` 필수(≥1), `daysOfWeek` 생략. 아무 날이나 수행 가능(유연)

**Response** `201`
```json
{
  "success": true,
  "message": "루틴 템플릿이 생성되었습니다.",
  "data": {
    "templateId": 1,
    "title": "아침 달리기",
    "categoryCode": "EXERCISE",
    "scheduleType": "SPECIFIC_DAYS",
    "daysOfWeek": ["MON", "WED", "FRI"],
    "targetCount": null,
    "challengeId": null
  }
}
```

**Error**
- `400 VALIDATION_FAILED` — 필수 필드 누락 / 유효하지 않은 카테고리 코드 / 스케줄 정합성 위반 (`SPECIFIC_DAYS`는 `daysOfWeek` 필수, `WEEKLY_COUNT`/`MONTHLY_COUNT`는 `targetCount` 필수, `DAILY`는 둘 다 생략) / 유효하지 않은 요일 코드

---

#### `GET /api/v1/routine-templates` — 내 루틴 템플릿 목록
- Auth: ✅
- Query: `categoryCode` (선택)

> **개인 템플릿만** 반환한다 (`challengeId` NULL, 미삭제). 챌린지 연결 템플릿은 챌린지/피드 맥락에서 노출한다.  
> 정렬: 최신 생성 순 (id 내림차순).

**Response** `200`
```json
{
  "success": true,
  "message": "루틴 템플릿 목록이 조회되었습니다.",
  "data": [
    {
      "templateId": 2,
      "title": "독서 30분",
      "categoryCode": "READING",
      "scheduleType": "WEEKLY_COUNT",
      "daysOfWeek": null,
      "targetCount": 3,
      "challengeId": null
    },
    {
      "templateId": 1,
      "title": "아침 달리기",
      "categoryCode": "EXERCISE",
      "scheduleType": "DAILY",
      "daysOfWeek": null,
      "targetCount": null,
      "challengeId": null
    }
  ]
}
```

---

#### `GET /api/v1/routine-templates/{templateId}` — 루틴 템플릿 상세
- Auth: ✅ (소유자만, 개인 템플릿만)

> 챌린지 연결 템플릿(`challengeId` NOT NULL)은 소유자여도 이 API로 조회할 수 없다. 챌린지 루틴은 챌린지/피드 맥락에서 노출한다.

**Response** `200`
```json
{
  "success": true,
  "message": "루틴 템플릿이 조회되었습니다.",
  "data": {
    "templateId": 1,
    "title": "아침 달리기",
    "categoryCode": "EXERCISE",
    "scheduleType": "DAILY",
    "daysOfWeek": null,
    "targetCount": null,
    "challengeId": null
  }
}
```

**Error**
- `404 ROUTINE_TEMPLATE_NOT_FOUND` — 없거나 삭제된 템플릿
- `403 FORBIDDEN` — 소유자가 아님 / 챌린지 연결 템플릿

---

#### `PATCH /api/v1/routine-templates/{templateId}` — 루틴 템플릿 수정
- Auth: ✅ (소유자만, 개인 템플릿만)

**Request** (부분 수정 — 보낸 필드만 변경)
```json
{
  "title": "저녁 달리기",
  "categoryCode": "EXERCISE",
  "scheduleType": "WEEKLY_COUNT",
  "targetCount": 3
}
```

> 템플릿은 루틴의 정의(`title`, `categoryCode`, `scheduleType`, `daysOfWeek`, `targetCount`)만 수정한다. 선호 수행 시각·요일은 `PATCH /api/v1/routines/{routineId}`에서 수정한다 (ADR-0035, ADR-0039).  
> 스케줄은 항상 한 묶음으로 수정한다 — `daysOfWeek`/`targetCount`는 `scheduleType`과 함께만 보낼 수 있고, 유형별 정합성(`SPECIFIC_DAYS`→`daysOfWeek`, `WEEKLY_COUNT`/`MONTHLY_COUNT`→`targetCount`, `DAILY`→둘 다 없음)을 지켜야 한다 (`ck_rt_schedule` 제약 미러링).  
> 챌린지 연결 템플릿(`challengeId` NOT NULL)은 이 API로 수정할 수 없다.

**Response** `200` — 수정 후 전체 템플릿 반환
```json
{
  "success": true,
  "message": "루틴 템플릿이 수정되었습니다.",
  "data": {
    "templateId": 1,
    "title": "저녁 달리기",
    "categoryCode": "EXERCISE",
    "scheduleType": "WEEKLY_COUNT",
    "daysOfWeek": null,
    "targetCount": 3,
    "challengeId": null
  }
}
```

**Error**
- `400 VALIDATION_FAILED` — 수정 필드 없음 / 유효하지 않은 카테고리 코드 / 스케줄 정합성 위반
- `403 FORBIDDEN` — 소유자가 아님 / 챌린지 연결 템플릿
- `404 ROUTINE_TEMPLATE_NOT_FOUND` — 없거나 삭제된 템플릿

---

#### `DELETE /api/v1/routine-templates/{templateId}` — 루틴 템플릿 삭제 (소프트)
- Auth: ✅ (소유자만, 개인 템플릿만)

> 물리 삭제하지 않고 `is_deleted = true` + `deleted_at`만 기록한다. 챌린지 연결 템플릿은 삭제할 수 없다.

**Response** `200`
```json
{
  "success": true,
  "message": "루틴 템플릿이 삭제되었습니다.",
  "data": null
}
```

**Error**
- `403 FORBIDDEN` — 소유자가 아님 / 챌린지 연결 템플릿
- `404 ROUTINE_TEMPLATE_NOT_FOUND` — 없거나 이미 삭제된 템플릿

> 구현 이슈: #55

---

### 3-2. 루틴 (활성 인스턴스)

#### `POST /api/v1/routines` — 루틴 시작
- Auth: ✅

**Request**
```json
{
  "routineTemplateId": 1,
  "startedAt": "2025-02-01",
  "endedAt": "2025-03-02",
  "preferredTime": "07:00:00"
}
```

> **개인 루틴만** 시작한다. 요청의 `routineTemplateId`는 요청자 본인 소유의 개인 템플릿이어야 한다.
> 챌린지 루틴 인스턴스는 이 API로 만들지 않는다 — `challenge.started` 수신 시 `preferredTime = null`로 자동 생성되며, 멤버가 이후 `PATCH /api/v1/routines/{routineId}`(#139)로 설정한다 (ADR-0032, ADR-0035).
> `preferredTime`(HH:mm:ss)은 알림 발송 기준 시각이며 선택값이다. 생략(`null`)하면 리마인더를 발송하지 않는다.
> **선호 요일(`preferredDays`)은 이 API에서 받지 않는다** — 루틴 시작 후 `PATCH /api/v1/routines/{routineId}`에서 설정한다. 응답에는 현재 값(생성 직후 `null`)이 포함된다 (ADR-0039).

**Response** `201`
```json
{
  "success": true,
  "message": "루틴이 시작되었습니다.",
  "data": {
    "routineId": 1,
    "routineTemplateId": 1,
    "title": "아침 달리기",
    "challengeId": null,
    "startedAt": "2025-02-01",
    "endedAt": "2025-03-02",
    "preferredTime": "07:00:00",
    "preferredDays": null,
    "isActive": true
  }
}
```

**Error**
- `400 VALIDATION_FAILED` — 필수 필드 누락 / 종료일 < 시작일 / preferredTime 형식 오류
- `403 FORBIDDEN` — 본인 소유 템플릿이 아님 / 챌린지 연결 템플릿(챌린지 루틴은 자동 생성)
- `404 ROUTINE_TEMPLATE_NOT_FOUND` — 없거나 삭제된 템플릿

> 구현 이슈: #56

---

#### `GET /api/v1/routines` — 내 루틴 목록
- Auth: ✅
- Query: `isActive` (true/false, 선택), `challengeId` (선택)

> 본인 루틴(개인 + 챌린지) 전체를 최신 생성 순(id 내림차순)으로 반환한다. `title`은 기반 템플릿 이름이다.

**Response** `200`
```json
{
  "success": true,
  "message": "루틴 목록이 조회되었습니다.",
  "data": [
    {
      "routineId": 1,
      "routineTemplateId": 1,
      "title": "아침 달리기",
      "challengeId": null,
      "startedAt": "2025-02-01",
      "endedAt": "2025-03-02",
      "preferredTime": "07:00:00",
      "preferredDays": ["MON", "WED", "FRI"],
      "isActive": true
    }
  ]
}
```

**Error**
- `400 VALIDATION_FAILED` — `isActive`가 true/false가 아님 / `challengeId`가 숫자가 아님

> 구현 이슈: #56

---

#### `GET /api/v1/routines/today` — 오늘의 루틴 목록
- Auth: ✅

**Response** `200`
```json
{
  "success": true,
  "message": "오늘의 루틴 목록이 조회되었습니다.",
  "data": [
    {
      "routineId": 1,
      "executionId": 101,
      "title": "아침 달리기",
      "category": "EXERCISE",
      "preferredTime": "07:00:00",
      "scheduledDate": "2025-02-15",
      "status": "PENDING",
      "completedAt": null
    }
  ]
}
```

---

#### `PATCH /api/v1/routines/{routineId}` — 선호 설정(시각·요일) 설정/수정
- Auth: ✅

**Request**
```json
{
  "preferredTime": "07:00:00",
  "preferredDays": ["MON", "WED", "FRI"]
}
```

> 본인 루틴 인스턴스의 선호 수행 시각(알림 발송 기준)과 선호 요일을 설정·수정한다. 각 필드 값이 그대로 새 값이 되며, `null`(또는 필드 생략)이면 해당 설정을 해제한다(시각 `null` → 리마인더 끔).
> **선호 요일은 soft다** — 알림/표시용일 뿐 완료를 제약하지 않는다. 빈도 유형(`WEEKLY_COUNT`/`MONTHLY_COUNT`) 루틴에서 "월화수 선호"여도 목·금·토에 수행해 목표 횟수를 채우면 달성이다 (ADR-0039).
> 개인/챌린지 루틴 모두 인스턴스 단위로 멤버가 직접 설정한다 (ADR-0035).
>
> 구현 이슈: #139 (선호 요일은 #149에서 추가). 루틴 시작 시 최초 시각 설정은 #56 `POST /routines`에서 처리.

**Response** `200`
```json
{
  "success": true,
  "message": "알림 설정이 저장되었습니다.",
  "data": {
    "routineId": 1,
    "preferredTime": "07:00:00",
    "preferredDays": ["MON", "WED", "FRI"]
  }
}
```

**Error**
- `400 VALIDATION_FAILED` — `preferredTime` 형식 오류(HH:mm:ss 아님) / 유효하지 않은 요일 코드
- `404 ROUTINE_NOT_FOUND` — 없거나 본인 소유가 아닌 루틴

---

#### `DELETE /api/v1/routines/{routineId}` — 루틴 중단
- Auth: ✅ (소유자만)

> 물리 삭제하지 않고 `is_active = false`로 전환한다(중단). 이미 중단된 루틴에 대해서도 멱등하게 동작한다.

**Response** `200`
```json
{
  "success": true,
  "message": "루틴이 중단되었습니다.",
  "data": null
}
```

**Error**
- `404 ROUTINE_NOT_FOUND` — 없거나 본인 소유가 아닌 루틴

> 구현 이슈: #56

---

### 3-3. 루틴 실행 기록

#### `GET /api/v1/routine-executions` — 실행 기록 조회
- Auth: ✅
- Query: `date` (YYYY-MM-DD), `startDate`, `endDate`, `routineId`, `status`

**Response** `200`
```json
{
  "success": true,
  "message": "루틴 실행 기록이 조회되었습니다.",
  "data": [
    {
      "executionId": 101,
      "routineId": 1,
      "title": "아침 달리기",
      "scheduledDate": "2025-02-15",
      "status": "COMPLETED",
      "completedAt": "2025-02-15T07:30:00Z",
      "photoUrl": "https://s3.../photo.jpg",
      "memo": "오늘도 완료!"
    }
  ]
}
```

---

#### `POST /api/v1/routine-executions/{executionId}/complete` — 루틴 완료 처리
- Auth: ✅

**Request**
```json
{
  "photoUrl": "https://s3.../photo.jpg",
  "memo": "오늘도 완료!"
}
```

**Response** `200`
```json
{
  "success": true,
  "message": "루틴이 완료 처리되었습니다.",
  "data": {
    "executionId": 101,
    "status": "COMPLETED",
    "completedAt": "2025-02-15T07:30:00Z",
    "feedCardId": 55
  }
}
```

---

#### `DELETE /api/v1/routine-executions/{executionId}/complete` — 완료 취소
- Auth: ✅

**Response** `200`
```json
{
  "success": true,
  "message": "루틴 완료가 취소되었습니다.",
  "data": {
    "executionId": 101,
    "status": "PENDING"
  }
}
```

---

### 3-4. 통계

#### `GET /api/v1/statistics/me` — 개인 통계
- Auth: ✅
- Query: `period` (daily/weekly/monthly), `date` (기준 날짜, default: today)

**Response** `200`
```json
{
  "success": true,
  "message": "개인 통계가 조회되었습니다.",
  "data": {
    "period": "weekly",
    "baseDate": "2025-02-15",
    "totalScheduled": 14,
    "completedCount": 11,
    "achievementRate": 78.57,
    "currentStreak": 5,
    "longestStreak": 12,
    "dailySummaries": [
      {
        "date": "2025-02-09",
        "totalCount": 2,
        "completedCount": 2,
        "achievementRate": 100.0
      }
    ]
  }
}
```

---

#### `GET /api/v1/statistics/challenges/{challengeId}` — 챌린지 통계
- Auth: ✅

**Response** `200`
```json
{
  "success": true,
  "message": "챌린지 통계가 조회되었습니다.",
  "data": {
    "challengeId": 1,
    "groupAchievementRate": 81.5,
    "totalMembers": 8,
    "ranking": [
      {
        "rank": 1,
        "userId": 3,
        "nickname": "박열심",
        "achievementRate": 93.33,
        "completedCount": 28
      }
    ]
  }
}
```

---

### 3-5. 피드

#### `GET /api/v1/feed/challenges/{challengeId}` — 챌린지 피드 조회
- Auth: ✅
- Query: `page`, `size`, `sort` (latest/popular)

**Response** `200`
```json
{
  "success": true,
  "message": "피드가 조회되었습니다.",
  "data": {
    "content": [
      {
        "feedCardId": 55,
        "userId": 1,
        "nickname": "김루틴",
        "routineTitle": "아침 달리기",
        "photoUrl": "https://s3.../photo.jpg",
        "memo": "오늘도 완료!",
        "reactions": [
          { "emoji": "🔥", "count": 3, "reacted": false },
          { "emoji": "👏", "count": 1, "reacted": true }
        ],
        "createdAt": "2025-02-15T07:30:00Z"
      }
    ],
    "page": 0,
    "size": 20,
    "hasNext": true
  }
}
```

---

#### `POST /api/v1/feed/{feedCardId}/reactions` — 리액션 추가
- Auth: ✅

**Request**
```json
{
  "emoji": "🔥"
}
```

**Response** `201`
```json
{
  "success": true,
  "message": "리액션이 추가되었습니다.",
  "data": {
    "reactionId": 10,
    "feedCardId": 55,
    "emoji": "🔥"
  }
}
```

---

#### `DELETE /api/v1/feed/{feedCardId}/reactions/{reactionId}` — 리액션 취소
- Auth: ✅

**Response** `200`
```json
{
  "success": true,
  "message": "리액션이 취소되었습니다."
}
```

---

## 4. 채팅 (Chat Service)

### 4-1. 채팅방

#### `GET /api/v1/chat/rooms` — 내 채팅방 목록
- Auth: ✅

**Response** `200`
```json
{
  "success": true,
  "message": "채팅방 목록이 조회되었습니다.",
  "data": [
    {
      "roomId": 1,
      "roomType": "CHALLENGE",
      "name": "30일 아침 운동 챌린지",
      "lastMessage": {
        "content": "오늘도 모두 파이팅!",
        "sentAt": "2025-02-15T08:00:00Z"
      },
      "unreadCount": 3
    }
  ]
}
```

---

#### `GET /api/v1/chat/rooms/{roomId}` — 채팅방 상세
- Auth: ✅

**Response** `200`
```json
{
  "success": true,
  "message": "채팅방 정보가 조회되었습니다.",
  "data": {
    "roomId": 1,
    "roomType": "CHALLENGE",
    "name": "30일 아침 운동 챌린지",
    "memberCount": 5,
    "members": [
      {
        "userId": 1,
        "nickname": "김루틴",
        "role": "OWNER"
      }
    ]
  }
}
```

---

#### `GET /api/v1/chat/rooms/{roomId}/messages` — 메시지 조회 (커서 기반)
- Auth: ✅
- Query: `before` (messageId), `size` (default: 50)

**Response** `200`
```json
{
  "success": true,
  "message": "메시지 목록이 조회되었습니다.",
  "data": {
    "messages": [
      {
        "messageId": 200,
        "senderId": 2,
        "senderNickname": "이도전",
        "messageType": "TEXT",
        "content": "오늘 드디어 완료했어요!",
        "imageUrl": null,
        "isDeleted": false,
        "createdAt": "2025-02-15T07:45:00Z"
      }
    ],
    "hasMore": true,
    "oldestMessageId": 200
  }
}
```

---

#### `POST /api/v1/chat/rooms/{roomId}/read` — 읽음 처리
- Auth: ✅

**Request**
```json
{
  "lastReadMessageId": 200
}
```

**Response** `200`
```json
{
  "success": true,
  "message": "읽음 처리가 완료되었습니다."
}
```

---

### 4-2. WebSocket (STOMP)

```
연결: WS /ws/chat
헤더: Authorization: Bearer {accessToken}
```

**메시지 구독 (수신)**
```
SUBSCRIBE /topic/chat.room.{roomId}
```

수신 메시지:
```json
{
  "messageId": 201,
  "roomId": 1,
  "senderId": 1,
  "senderNickname": "김루틴",
  "messageType": "TEXT",
  "content": "파이팅!",
  "imageUrl": null,
  "createdAt": "2025-02-15T08:01:00Z"
}
```

**메시지 전송**
```
SEND /app/chat.send
```

```json
{
  "roomId": 1,
  "messageType": "TEXT",
  "content": "파이팅!",
  "imageUrl": null
}
```

---

## 5. 알림 (Notification Service)

### 5-1. 알림 히스토리

#### `GET /api/v1/notifications` — 알림 목록 조회
- Auth: ✅
- Query: `page`, `size`, `isRead` (true/false)

**Response** `200`
```json
{
  "success": true,
  "message": "알림 목록이 조회되었습니다.",
  "data": {
    "content": [
      {
        "notificationId": 1,
        "type": "ROUTINE_START",
        "title": "아침 달리기 시작 알림",
        "body": "오늘의 루틴을 시작할 시간입니다!",
        "isRead": false,
        "sentAt": "2025-02-15T06:55:00Z",
        "referenceType": "ROUTINE",
        "referenceId": 1
      }
    ],
    "page": 0,
    "size": 20,
    "hasNext": false
  }
}
```

---

#### `PATCH /api/v1/notifications/{notificationId}/read` — 알림 읽음 처리
- Auth: ✅

**Response** `200`
```json
{
  "success": true,
  "message": "알림이 읽음 처리되었습니다."
}
```

---

#### `POST /api/v1/notifications/read-all` — 전체 읽음 처리
- Auth: ✅

**Response** `200`
```json
{
  "success": true,
  "message": "모든 알림이 읽음 처리되었습니다."
}
```

---

### 5-2. 실시간 알림 (SSE)

#### `GET /api/v1/notifications/stream` — SSE 연결
- Auth: ✅
- 헤더: `Accept: text/event-stream`

수신 이벤트:
```
event: notification
data: {"notificationId":2,"type":"CHALLENGE_EVENT","title":"새 멤버가 참여했습니다.","body":"이도전님이 챌린지에 참여했습니다.","sentAt":"2025-02-15T09:00:00Z"}
```

---

### 5-3. 알림 설정

#### `GET /api/v1/notifications/settings` — 알림 설정 조회
- Auth: ✅

**Response** `200`
```json
{
  "success": true,
  "message": "알림 설정이 조회되었습니다.",
  "data": {
    "routineReminderEnabled": true,
    "challengeEventEnabled": true,
    "quietStartTime": null,
    "quietEndTime": null
  }
}
```

---

#### `PATCH /api/v1/notifications/settings` — 알림 설정 수정
- Auth: ✅

**Request**
```json
{
  "routineReminderEnabled": false,
  "challengeEventEnabled": true
}
```

**Response** `200`
```json
{
  "success": true,
  "message": "알림 설정이 변경되었습니다.",
  "data": {
    "routineReminderEnabled": false,
    "challengeEventEnabled": true
  }
}
```

---

## 부록 — 주요 Enum 값

| 필드 | 허용 값 |
|---|---|
| `users.role` | `USER`, `ADMIN` |
| `challenges.status` | `WAITING`, `ACTIVE`, `ENDED` |
| `challenge_members.role` | `LEADER`, `MEMBER` |
| `challenge_members.status` | `ACTIVE`, `LEFT`, `EXPELLED` |
| `routine_templates.schedule_type` | `DAILY`, `SPECIFIC_DAYS`, `WEEKLY_COUNT`, `MONTHLY_COUNT` (챌린지는 `SPECIFIC_DAYS` 불가) |
| `routine_templates.days_of_week` / `routines.preferred_days` | 요일 비트마스크 (bit0=월 … bit6=일). API는 `["MON","TUE","WED","THU","FRI","SAT","SUN"]` 배열로 표현 |
| `categories.code` / `routine_templates.category_code` / `challenges.category_code` | `EXERCISE`, `READING`, `STUDY`, `LANGUAGE`, `HEALTH`, `SLEEP`, `DIET`, `MEDITATION`, `SELF_IMPROVE`, `PRODUCTIVITY`, `HOBBY`, `QUIT_HABIT` |
| `routine_executions.status` | `PENDING`, `COMPLETED`, `MISSED` |
| `chat_rooms.room_type` | `CHALLENGE`, `DIRECT` |
| `chat_room_members.role` | `OWNER`, `MEMBER` |
| `chat_messages.message_type` | `TEXT`, `IMAGE`, `SYSTEM` |
| `notification_history.type` | `ROUTINE_START`, `DEADLINE`, `CHALLENGE_EVENT` |
| `notification_history.reference_type` | `CHALLENGE`, `ROUTINE`, `FEED_CARD`, `CHAT_ROOM` |
| `outbox.status` | `PENDING`, `PUBLISHED`, `FAILED` |
| `inbox.status` | `RECEIVED`, `PROCESSED`, `FAILED` |

---

## 부록 — errorCode 목록

> `common-core/.../exception/ErrorCode.java` 기준 (코드와 동기화 유지)

| errorCode | HTTP | 설명 |
|---|---|---|
| `VALIDATION_FAILED` | 400 | 유효성 검사 실패 |
| `UNAUTHORIZED` | 401 | 토큰 없음 / 만료 |
| `INVALID_CREDENTIALS` | 401 | 이메일 또는 비밀번호 불일치 |
| `FORBIDDEN` | 403 | 권한 없음 |
| `NOT_CHALLENGE_MEMBER` | 403 | 챌린지 멤버가 아님 |
| `CHALLENGE_MEMBER_EXPELLED` | 403 | 강퇴된 사용자의 재참여 시도 |
| `CHAT_NOT_MEMBER` | 403 | 채팅방 멤버가 아님 |
| `USER_NOT_FOUND` | 404 | 사용자 없음 |
| `CHALLENGE_NOT_FOUND` | 404 | 챌린지 없음 |
| `ROUTINE_TEMPLATE_NOT_FOUND` | 404 | 루틴 템플릿 없음 |
| `ROUTINE_NOT_FOUND` | 404 | 루틴 없음 |
| `EXECUTION_NOT_FOUND` | 404 | 루틴 실행 기록 없음 |
| `CHAT_ROOM_NOT_FOUND` | 404 | 채팅방 없음 |
| `NOTIFICATION_NOT_FOUND` | 404 | 알림 없음 |
| `EMAIL_ALREADY_EXISTS` | 409 | 이메일 중복 |
| `NICKNAME_ALREADY_EXISTS` | 409 | 닉네임 중복 |
| `NICKNAME_CHANGE_COOLDOWN_ACTIVE` | 409 | 닉네임 변경 30일 쿨다운 미경과 |
| `CHALLENGE_ALREADY_JOINED` | 409 | 이미 참여한 챌린지 |
| `CHALLENGE_FULL` | 409 | 챌린지 인원 초과 |
| `CHALLENGE_ALREADY_ENDED` | 409 | 이미 종료된 챌린지 |
| `CHALLENGE_ALREADY_ACTIVE` | 409 | 이미 시작된 챌린지 — MVP에서 WAITING 상태가 아닌 챌린지 참여 시도 시 |
| `CHALLENGE_NOT_STARTED` | 409 | 아직 시작되지 않은 챌린지 |
| `EXECUTION_ALREADY_COMPLETED` | 409 | 이미 완료된 수행 기록 |
| `TOO_MANY_REQUESTS` | 429 | Rate Limit 초과 |
| `INTERNAL_SERVER_ERROR` | 500 | 서버 내부 오류 |
| `SERVICE_UNAVAILABLE` | 503 | 일시적 서버 문제 (Circuit Breaker 등) |
