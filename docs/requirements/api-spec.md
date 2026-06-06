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
- 구현 시점: 추후 별도 이슈

**구현 메모**
- 이미지 저장소의 객체 삭제/교체를 위해 `users.profile_image_object_key VARCHAR(500) NULL` 컬럼을 추가한다.
- `profileImageUrl`은 클라이언트 표시용 URL이며, `profileImageObjectKey`는 S3/R2/MinIO 등 object storage 내부 객체 식별자로 사용한다.
- 권장 저장 key 예시: `users/{userId}/profile/{uuid}.webp`

**Request**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `image` | file | ✅ | `image/jpeg`, `image/png`, `image/webp` |

**Response** `200`
```json
{
  "success": true,
  "message": "프로필 이미지가 변경되었습니다.",
  "data": {
    "userId": 1,
    "email": "user@example.com",
    "nickname": "새닉네임",
    "profileImageUrl": "https://cdn.example.com/users/1/profile/uuid.webp"
  }
}
```

---

#### `DELETE /api/v1/users/me/profile-image` — 프로필 이미지 삭제
- Auth: ✅
- 구현 시점: 추후 별도 이슈

삭제 시 DB의 `profile_image_url`, `profile_image_object_key`를 함께 `NULL`로 변경하고, 트랜잭션 커밋 이후 기존 storage 객체를 삭제한다.

**Response** `200`
```json
{
  "success": true,
  "message": "프로필 이미지가 삭제되었습니다.",
  "data": {
    "userId": 1,
    "email": "user@example.com",
    "nickname": "새닉네임",
    "profileImageUrl": null
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

**Request**
```json
{
  "title": "30일 아침 운동 챌린지",
  "description": "매일 아침 30분 운동하기",
  "categoryCode": "EXERCISE",
  "isPublic": true,
  "maxMembers": 10,
  "categoryCode": "EXERCISE",
  "startedAt": "2025-02-01",
  "endedAt": "2025-03-02"
}
```

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
| 상태 | 멤버 수 | 비공개→공개 | 공개→비공개 |
|---|---|:---:|:---:|
| WAITING / ACTIVE | 1명 (방장만) | ✅ | ✅ 초대코드 자동생성 |
| WAITING / ACTIVE | 2명 이상 | ✅ | ❌ |
| ENDED | 무관 | ❌ | ❌ |

- 비공개→공개 전환 시 기존 `inviteCode` 유지 (다시 비공개 전환 시 재사용).
- 공개→비공개 전환 시 `inviteCode`가 없으면 자동 생성.

**그 외 필드 수정 정책** (`title`, `description`, `maxMembers`, `startedAt`, `endedAt`)
- `WAITING` 상태인 챌린지만 수정 가능하다.
  - **방장 혼자 (1명)**: 위 필드 모두 수정 가능.
  - **멤버 2명 이상**: `description`과 `maxMembers` 증가만 허용. 나머지 변경 시 `400` 반환.
  - **항상 변경 불가**: `categoryCode`, `repeatType`, `repeatValue`, `creatorUserId`.
- 날짜 제약:
  - `startedAt`은 오늘 이후로만 변경 가능하며, 반드시 `endedAt`보다 빨라야 한다.
  - `endedAt`은 `startedAt` 이후로만 변경 가능하다.

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

### 3-1. 루틴 템플릿

#### `POST /api/v1/routine-templates` — 루틴 템플릿 생성
- Auth: ✅

**Request**
```json
{
  "title": "아침 달리기",
  "category": "EXERCISE",
  "repeatType": "DAILY",
  "repeatValue": null,
  "preferredTime": "07:00:00",
  "ownerType": "PERSONAL"
}
```

**Response** `201`
```json
{
  "success": true,
  "message": "루틴 템플릿이 생성되었습니다.",
  "data": {
    "templateId": 1,
    "title": "아침 달리기",
    "category": "EXERCISE",
    "repeatType": "DAILY",
    "repeatValue": null,
    "preferredTime": "07:00:00",
    "ownerType": "PERSONAL",
    "ownerId": 1
  }
}
```

---

#### `GET /api/v1/routine-templates` — 내 루틴 템플릿 목록
- Auth: ✅
- Query: `ownerType` (PERSONAL/CHALLENGE), `challengeId`

**Response** `200`
```json
{
  "success": true,
  "message": "루틴 템플릿 목록이 조회되었습니다.",
  "data": [ ... ]
}
```

---

#### `PATCH /api/v1/routine-templates/{templateId}` — 루틴 템플릿 수정
- Auth: ✅

**Request**
```json
{
  "title": "저녁 달리기",
  "preferredTime": "19:00:00"
}
```

**Response** `200`
```json
{
  "success": true,
  "message": "루틴 템플릿이 수정되었습니다.",
  "data": {
    "templateId": 1,
    "title": "저녁 달리기",
    "preferredTime": "19:00:00"
  }
}
```

---

#### `DELETE /api/v1/routine-templates/{templateId}` — 루틴 템플릿 삭제 (소프트)
- Auth: ✅

**Response** `200`
```json
{
  "success": true,
  "message": "루틴 템플릿이 삭제되었습니다."
}
```

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
  "challengeId": null
}
```

**Response** `201`
```json
{
  "success": true,
  "message": "루틴이 시작되었습니다.",
  "data": {
    "routineId": 1,
    "routineTemplateId": 1,
    "title": "아침 달리기",
    "startedAt": "2025-02-01",
    "endedAt": "2025-03-02",
    "isActive": true
  }
}
```

---

#### `GET /api/v1/routines` — 내 루틴 목록
- Auth: ✅
- Query: `isActive` (true/false), `challengeId`

**Response** `200`
```json
{
  "success": true,
  "message": "루틴 목록이 조회되었습니다.",
  "data": [ ... ]
}
```

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

#### `DELETE /api/v1/routines/{routineId}` — 루틴 중단
- Auth: ✅

**Response** `200`
```json
{
  "success": true,
  "message": "루틴이 중단되었습니다."
}
```

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
| `routine_templates.owner_type` | `PERSONAL`, `CHALLENGE` |
| `routine_templates.repeat_type` | `DAILY`, `WEEKLY`, `WEEKLY_N`, `MONTHLY_N` |
| `routine_templates.category` | `EXERCISE`, `STUDY`, `READING`, `LIFESTYLE` |
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
