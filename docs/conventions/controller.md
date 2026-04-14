# Controller / Request DTO 구현 패턴

## Controller 패턴

```java
@RestController
@RequestMapping("/api/v1/challenges")
@RequiredArgsConstructor
public class ChallengeController {

    private final ChallengeService challengeService;

    // @Valid 검사 실패 시 MethodArgumentNotValidException 자동 발생 → GlobalExceptionHandler 처리
    // BindingResult 파라미터 선언 금지 — 없어야 Spring이 자동으로 예외를 던짐
    @PostMapping
    public ResponseEntity<ApiResponse<ChallengeDto.CreateResponse>> create(
            @RequestBody @Valid ChallengeDto.CreateRequest request,
            @RequestHeader("X-User-Id") Long userId) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("챌린지 생성에 성공했습니다.",
                        challengeService.create(request, userId)));
    }

    @GetMapping("/{challengeId}")
    public ResponseEntity<ApiResponse<ChallengeDto.DetailResponse>> get(
            @PathVariable Long challengeId,
            @RequestHeader("X-User-Id") Long userId) {

        return ResponseEntity.ok(
                ApiResponse.ok("챌린지 조회에 성공했습니다.",
                        challengeService.get(challengeId, userId)));
    }
}
```

**규칙 요약:**
- `userId`는 항상 `@RequestHeader("X-User-Id")`로 수신 — 서비스에서 JWT 파싱 금지
- `BindingResult` 파라미터 선언 금지 — 없어야 Spring이 자동으로 예외를 던짐
- 반환 타입: `ResponseEntity<ApiResponse<T>>`

---

## Request DTO 유효성 검사 예시

```java
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public static class CreateRequest {

    @NotBlank(message = "제목은 필수입니다.")
    @Size(max = 100, message = "제목은 100자 이하여야 합니다.")
    private String title;

    @NotBlank(message = "설명은 필수입니다.")
    @Size(max = 500, message = "설명은 500자 이하여야 합니다.")
    private String description;

    @NotNull(message = "시작일은 필수입니다.")
    @FutureOrPresent(message = "시작일은 오늘 이후여야 합니다.")
    private LocalDate startDate;

    @NotNull(message = "종료일은 필수입니다.")
    @Future(message = "종료일은 미래여야 합니다.")
    private LocalDate endDate;

    @NotNull(message = "최대 인원은 필수입니다.")
    @Min(value = 2, message = "최소 2명 이상이어야 합니다.")
    @Max(value = 100, message = "최대 100명까지 가능합니다.")
    private Integer maxMembers;

    @NotNull(message = "공개 여부는 필수입니다.")
    private Boolean isPublic;
}
```

**메시지 작성 규칙:**
- 한국어로 작성
- `"필드명 + 조건"` 형태로 명확하게
- `message` 속성 항상 직접 지정 — 기본 메시지(`must not be blank`) 사용 금지

```java
// 나쁜 예
@NotBlank
private String title;

// 좋은 예
@NotBlank(message = "제목은 필수입니다.")
private String title;
```
