# 예외 처리 구현 패턴

## 모듈별 위치

| 클래스 | 모듈 | 패키지 |
|---|---|---|
| `ErrorCode` (enum) | common-core | `com.routinely.core.exception` |
| `BusinessException` | common-core | `com.routinely.core.exception` |
| `GlobalExceptionHandler` | common-web | `com.routinely.web.handler` |

---

## ErrorCode (enum)

각 에러 코드는 `code`, `message`, `httpStatus` 필드를 가진다.

```java
@Getter
public enum ErrorCode {
    VALIDATION_FAILED("VALIDATION_FAILED", "유효성 검사에 실패했습니다.", HttpStatus.BAD_REQUEST),
    CHALLENGE_NOT_FOUND("CHALLENGE_NOT_FOUND", "챌린지를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    CHALLENGE_ALREADY_JOINED("CHALLENGE_ALREADY_JOINED", "이미 참여한 챌린지입니다.", HttpStatus.CONFLICT),
    // ... 서비스별 추가;

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String message, HttpStatus httpStatus) { ... }
}
```

전체 에러 코드 목록 → `common-core/.../exception/ErrorCode.java`

---

## BusinessException

도메인 예외는 단일 클래스로 통일. 도메인별 예외 클래스 다수 생성 금지.

```java
@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) { ... }
    public BusinessException(ErrorCode errorCode, String customMessage) { ... }
}
```

---

## GlobalExceptionHandler (common-web)

`@Valid` 검사 실패 시 Spring이 `MethodArgumentNotValidException`을 자동으로 던진다.
컨트롤러에 `BindingResult` 파라미터를 선언하지 않는다.

```java
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        log.warn("Validation failed: {}", errors);
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(
                        ErrorCode.VALIDATION_FAILED.getCode(),
                        ErrorCode.VALIDATION_FAILED.getMessage(),
                        errors
                ));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<?>> handleBusiness(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("Business exception: code={}, message={}", errorCode.getCode(), e.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.fail(errorCode.getCode(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleException(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.fail(
                        ErrorCode.INTERNAL_SERVER_ERROR.getCode(),
                        ErrorCode.INTERNAL_SERVER_ERROR.getMessage()
                ));
    }
}
```

---

## 유효성 검사 실패 응답 포맷

```json
{
  "success": false,
  "message": "유효성 검사에 실패했습니다.",
  "errorCode": "VALIDATION_FAILED",
  "data": {
    "email": "이메일 형식이 올바르지 않습니다.",
    "password": "비밀번호는 8자 이상이어야 합니다."
  }
}
```

## 유효성 검사 흐름

```
클라이언트 요청
    └── Controller (@RequestBody @Valid — BindingResult 없음)
          └── MethodArgumentNotValidException 자동 발생
                └── GlobalExceptionHandler → 400 응답
```
