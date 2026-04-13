# 예외 처리 구현 패턴

## 모듈별 위치

| 클래스 | 모듈 | 패키지 |
|---|---|---|
| `ErrorCode` (enum) | common-core | `com.routinely.core.exception` |
| `BusinessException` | common-core | `com.routinely.core.exception` |
| `ValidationException` | common-core | `com.routinely.core.exception` |
| `ValidationAdvice` | common-web | `com.routinely.web.aop` |
| `GlobalExceptionHandler` | common-web | `com.routinely.web.handler` |

---

## ErrorCode (enum)

```java
public enum ErrorCode {
    // 공통
    VALIDATION_FAILED("VALIDATION_FAILED", "유효성 검사에 실패했습니다.", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED("UNAUTHORIZED", "인증이 필요합니다.", HttpStatus.UNAUTHORIZED),

    // Challenge
    CHALLENGE_NOT_FOUND("CHALLENGE_NOT_FOUND", "챌린지를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    CHALLENGE_ALREADY_JOINED("CHALLENGE_ALREADY_JOINED", "이미 참여한 챌린지입니다.", HttpStatus.CONFLICT),

    // Routine
    ROUTINE_NOT_FOUND("ROUTINE_NOT_FOUND", "루틴을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),

    // ... 서비스별 추가
    ;

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
```

---

## BusinessException / ValidationException

```java
// 도메인 예외 — 단일 클래스로 통일 (도메인별 예외 클래스 다수 생성 금지)
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

// 유효성 검사 예외 — ValidationAdvice AOP가 던짐
@Getter
public class ValidationException extends RuntimeException {
    private final Map<String, String> errors;

    public ValidationException(Map<String, String> errors) {
        super(ErrorCode.VALIDATION_FAILED.getMessage());
        this.errors = errors;
    }
}
```

---

## ValidationAdvice AOP (common-web)

컨트롤러 메서드 파라미터에 `BindingResult`가 있을 때 자동으로 유효성 오류를 감지해 예외를 던진다.
컨트롤러에서 `bindingResult.hasErrors()` 체크 코드를 작성하지 않아도 된다.

```java
@Component
@Aspect
public class ValidationAdvice {

    @Pointcut("@annotation(org.springframework.web.bind.annotation.PostMapping)")
    public void postMapping() {}

    @Pointcut("@annotation(org.springframework.web.bind.annotation.PutMapping)")
    public void putMapping() {}

    @Pointcut("@annotation(org.springframework.web.bind.annotation.PatchMapping)")
    public void patchMapping() {}

    @Around("postMapping() || putMapping() || patchMapping()")
    public Object validationAdvice(ProceedingJoinPoint pjp) throws Throwable {
        for (Object arg : pjp.getArgs()) {
            if (arg instanceof BindingResult br && br.hasErrors()) {
                Map<String, String> errors = new LinkedHashMap<>();
                br.getFieldErrors().forEach(e -> errors.put(e.getField(), e.getDefaultMessage()));
                throw new ValidationException(errors);
            }
        }
        return pjp.proceed();
    }
}
```

---

## GlobalExceptionHandler (common-web)

```java
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiResponse<?>> handleValidation(ValidationException e) {
        log.warn("Validation failed: {}", e.getErrors());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.VALIDATION_FAILED.getCode(), e.getMessage(), e.getErrors()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<?>> handleBusiness(BusinessException e) {
        log.warn("Business exception: {}", e.getMessage());
        ErrorCode ec = e.getErrorCode();
        return ResponseEntity.status(ec.getHttpStatus())
                .body(ApiResponse.fail(ec.getCode(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleException(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.fail("INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다."));
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
    └── Controller (@RequestBody @Valid + BindingResult)
          └── ValidationAdvice AOP (BindingResult.hasErrors() 감지)
                └── ValidationException 발생
                      └── GlobalExceptionHandler → 400 응답
```
