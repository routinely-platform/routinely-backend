# ADR-0018: 에러 코드 표준화 전략

## Status
Accepted

---

## Context

Routinely는 `ApiResponse<T>` 공통 응답 DTO를 사용한다.

실패 응답 시 HTTP 상태코드만으로는 클라이언트가 에러의 원인을 구분하기 어렵다.

예를 들어, HTTP 404 하나로는 아래 상황을 구분할 수 없다:
- 사용자를 찾을 수 없음
- 챌린지를 찾을 수 없음
- 루틴을 찾을 수 없음

클라이언트(React)가 에러 유형별로 다른 UX(메시지, 리다이렉트, 재시도 등)를 제공하려면
HTTP 상태코드 외에 머신이 읽을 수 있는 에러 식별자가 필요하다.

---

## Decision

모든 에러 응답은 `errorCode` 필드를 포함한다.

```json
{
  "success": false,
  "message": "챌린지를 찾을 수 없습니다.",
  "errorCode": "CHALLENGE_NOT_FOUND"
}
```

**에러 코드 규칙:**

- **형식**: `UPPER_SNAKE_CASE`
- **구성**: `{도메인}_{설명}` 또는 공통 코드
- **타입**: `String` — Enum으로 관리하여 오타 방지
- **위치**: 각 서비스의 `ErrorCode` enum에 정의 / `libs/common`에 공통 코드 정의

---

## Implementation

### ErrorStatus Enum (공통)

`spring-web` 의존 없이 HTTP 상태코드를 표현. `common-core`에 위치한다.

```java
// libs/common-core
@Getter
public enum ErrorStatus {
    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    CONFLICT(409),
    INTERNAL_SERVER_ERROR(500);

    private final int code;
}
```

### ErrorCode Enum (공통)

```java
// libs/common-core
@Getter
public enum ErrorCode {
    // Common
    VALIDATION_FAILED("VALIDATION_FAILED", "유효성 검사에 실패했습니다.", ErrorStatus.BAD_REQUEST),
    UNAUTHORIZED("UNAUTHORIZED", "인증이 필요합니다.", ErrorStatus.UNAUTHORIZED),
    FORBIDDEN("FORBIDDEN", "접근 권한이 없습니다.", ErrorStatus.FORBIDDEN),
    INTERNAL_SERVER_ERROR("INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다.", ErrorStatus.INTERNAL_SERVER_ERROR),

    // User
    USER_NOT_FOUND("USER_NOT_FOUND", "사용자를 찾을 수 없습니다.", ErrorStatus.NOT_FOUND),
    EMAIL_ALREADY_EXISTS("EMAIL_ALREADY_EXISTS", "이미 사용 중인 이메일입니다.", ErrorStatus.CONFLICT),
    NICKNAME_ALREADY_EXISTS("NICKNAME_ALREADY_EXISTS", "이미 사용 중인 닉네임입니다.", ErrorStatus.CONFLICT),
    INVALID_CREDENTIALS("INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.", ErrorStatus.UNAUTHORIZED),

    // Challenge
    CHALLENGE_NOT_FOUND("CHALLENGE_NOT_FOUND", "챌린지를 찾을 수 없습니다.", ErrorStatus.NOT_FOUND),
    CHALLENGE_ALREADY_JOINED("CHALLENGE_ALREADY_JOINED", "이미 참여한 챌린지입니다.", ErrorStatus.CONFLICT),
    CHALLENGE_FULL("CHALLENGE_FULL", "챌린지 인원이 가득 찼습니다.", ErrorStatus.CONFLICT),
    NOT_CHALLENGE_MEMBER("NOT_CHALLENGE_MEMBER", "챌린지 멤버가 아닙니다.", ErrorStatus.FORBIDDEN),
    CHALLENGE_ALREADY_ENDED("CHALLENGE_ALREADY_ENDED", "이미 종료된 챌린지입니다.", ErrorStatus.CONFLICT),
    CHALLENGE_NOT_STARTED("CHALLENGE_NOT_STARTED", "아직 시작되지 않은 챌린지입니다.", ErrorStatus.CONFLICT),

    // Routine
    ROUTINE_TEMPLATE_NOT_FOUND("ROUTINE_TEMPLATE_NOT_FOUND", "루틴 템플릿을 찾을 수 없습니다.", ErrorStatus.NOT_FOUND),
    ROUTINE_NOT_FOUND("ROUTINE_NOT_FOUND", "루틴을 찾을 수 없습니다.", ErrorStatus.NOT_FOUND),
    EXECUTION_NOT_FOUND("EXECUTION_NOT_FOUND", "루틴 수행 기록을 찾을 수 없습니다.", ErrorStatus.NOT_FOUND),
    EXECUTION_ALREADY_COMPLETED("EXECUTION_ALREADY_COMPLETED", "이미 완료된 수행 기록입니다.", ErrorStatus.CONFLICT),

    // Chat
    CHAT_ROOM_NOT_FOUND("CHAT_ROOM_NOT_FOUND", "채팅방을 찾을 수 없습니다.", ErrorStatus.NOT_FOUND),
    CHAT_NOT_MEMBER("CHAT_NOT_MEMBER", "채팅방 멤버가 아닙니다.", ErrorStatus.FORBIDDEN),

    // Notification
    NOTIFICATION_NOT_FOUND("NOTIFICATION_NOT_FOUND", "알림을 찾을 수 없습니다.", ErrorStatus.NOT_FOUND);

    private final String code;
    private final String message;
    private final ErrorStatus status;
}
```

### GlobalExceptionHandler

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity
            .status(errorCode.getStatus().getCode())
            .body(ApiResponse.fail(errorCode.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
            .body(ApiResponse.fail(
                ErrorCode.VALIDATION_FAILED.getCode(),
                ErrorCode.VALIDATION_FAILED.getMessage(),
                errors
            ));
    }
}
```

### BusinessException

```java
// libs/common-core
@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String customMessage) {
        super(customMessage);
        this.errorCode = errorCode;
    }
}
```

---

## 에러 코드 목록

### 공통 (Common)

| errorCode | HTTP 상태 | 설명 |
|-----------|-----------|------|
| `INVALID_INPUT` | 400 | 유효성 검사 실패 |
| `UNAUTHORIZED` | 401 | 토큰 없음 / 만료 / 유효하지 않음 |
| `FORBIDDEN` | 403 | 권한 없음 |
| `INTERNAL_SERVER_ERROR` | 500 | 서버 내부 오류 |

### User Service

| errorCode | HTTP 상태 | 설명 |
|-----------|-----------|------|
| `USER_NOT_FOUND` | 404 | 사용자를 찾을 수 없음 |
| `EMAIL_ALREADY_EXISTS` | 409 | 이미 사용 중인 이메일 |
| `NICKNAME_ALREADY_EXISTS` | 409 | 이미 사용 중인 닉네임 |
| `INVALID_CREDENTIALS` | 401 | 이메일 또는 비밀번호 불일치 |

### Challenge Service

| errorCode | HTTP 상태 | 설명 |
|-----------|-----------|------|
| `CHALLENGE_NOT_FOUND` | 404 | 챌린지를 찾을 수 없음 |
| `CHALLENGE_ALREADY_JOINED` | 409 | 이미 활성 참여 중인 챌린지 (탈퇴 후 재참여는 허용) |
| `CHALLENGE_FULL` | 409 | 챌린지 정원 초과 |
| `NOT_CHALLENGE_MEMBER` | 403 | 챌린지 멤버가 아님 |
| `CHALLENGE_NOT_STARTED` | 403 | 챌린지가 아직 시작되지 않음 |
| `CHALLENGE_ALREADY_ENDED` | 403 | 이미 종료된 챌린지 |

### Routine Service

| errorCode | HTTP 상태 | 설명 |
|-----------|-----------|------|
| `ROUTINE_TEMPLATE_NOT_FOUND` | 404 | 루틴 템플릿을 찾을 수 없음 |
| `ROUTINE_NOT_FOUND` | 404 | 루틴 실행 기록을 찾을 수 없음 |
| `EXECUTION_NOT_FOUND` | 404 | 루틴 실행 레코드를 찾을 수 없음 |
| `EXECUTION_ALREADY_COMPLETED` | 409 | 이미 완료 처리된 루틴 |

### Chat Service

| errorCode | HTTP 상태 | 설명 |
|-----------|-----------|------|
| `CHAT_ROOM_NOT_FOUND` | 404 | 채팅방을 찾을 수 없음 |
| `CHAT_NOT_MEMBER` | 403 | 채팅방 멤버가 아님 |

### Notification Service

| errorCode | HTTP 상태 | 설명 |
|-----------|-----------|------|
| `NOTIFICATION_NOT_FOUND` | 404 | 알림을 찾을 수 없음 |

---

## Consequences

### Positive

- 클라이언트가 에러 원인을 정확하게 식별하고 UX별 분기 처리 가능
- `ErrorCode` enum으로 오타 방지 및 코드 자동완성 지원
- 에러 코드가 문서화되어 프론트엔드와 명확한 계약 형성
- HTTP 상태코드 + errorCode 조합으로 이중 검증 가능

### Negative

- 새 에러 상황 발생 시 enum에 코드를 추가해야 함
- 에러 코드가 많아질수록 enum 관리 부담 증가
  - 완화: 서비스별 패키지 분리 또는 도메인별 enum 분리 가능

---

## Architectural Principle

> HTTP 상태코드는 "통신 계층의 결과"이고,
> errorCode는 "비즈니스 계층의 원인"이다.
>
> 두 정보를 함께 반환하여 클라이언트가 정밀한 에러 처리를 할 수 있도록 한다.
