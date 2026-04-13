# Service / DTO 구현 패턴

## Service — interface + Impl 분리

```java
// 인터페이스 정의
public interface ChallengeService {
    ChallengeDto.CreateResponse create(ChallengeDto.CreateRequest request, Long userId);
    void join(Long challengeId, Long userId);
}

@Service
@Transactional(readOnly = true)   // 클래스 기본값 — 조회 최적화
@RequiredArgsConstructor
@Slf4j
public class ChallengeServiceImpl implements ChallengeService {

    private final ChallengeRepository challengeRepository;

    @Override
    @Transactional   // 쓰기 메서드는 반드시 오버라이드
    public ChallengeDto.CreateResponse create(ChallengeDto.CreateRequest request, Long userId) {
        Challenge challenge = Challenge.builder()
                .title(request.getTitle())
                .build();
        challengeRepository.save(challenge);
        return ChallengeDto.CreateResponse.from(challenge);
    }

    @Override
    @Transactional
    public void join(Long challengeId, Long userId) {
        Challenge challenge = findChallengeByIdOrThrow(challengeId);
        challenge.addMember(userId);
        log.info("Challenge joined - challengeId: {}, userId: {}", challengeId, userId);
    }

    // findOrThrow 헬퍼 — orElseThrow 중복 제거
    private Challenge findChallengeByIdOrThrow(Long challengeId) {
        return challengeRepository.findById(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
    }
}
```

**규칙 요약:**
- 클래스 레벨 `@Transactional(readOnly = true)` 기본 적용
- 쓰기 메서드에 `@Transactional` 오버라이드 필수 — 빠뜨리면 readOnly 트랜잭션으로 INSERT 시도하여 오류 발생
- `findXxxByIdOrThrow()` private 헬퍼 메서드로 `orElseThrow` 중복 제거

---

## DTO — 도메인별 중첩 static class

```java
// presentation/rest/dto/ChallengeDto.java
public class ChallengeDto {

    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)  // Jackson 역직렬화용 기본 생성자 필수
    public static class CreateRequest {
        @NotBlank(message = "제목은 필수입니다.")
        private String title;

        @NotNull
        private LocalDate startDate;
    }

    @Getter
    @Builder
    public static class CreateResponse {
        private Long   challengeId;
        private String title;
        private String status;

        public static CreateResponse from(Challenge challenge) {
            return CreateResponse.builder()
                    .challengeId(challenge.getId())
                    .title(challenge.getTitle())
                    .status(challenge.getStatus().name())
                    .build();
        }
    }

    @Getter
    @Builder
    public static class SummaryResponse {
        private Long   challengeId;
        private String title;
        private int    memberCount;
    }
}
```

사용 시: `ChallengeDto.CreateRequest`, `ChallengeDto.SummaryResponse`

**규칙 요약:**
- Request DTO: `@Getter` + `@NoArgsConstructor(PROTECTED)` — Jackson 역직렬화에 기본 생성자 필요
- Response DTO: `@Getter` + `@Builder` — 서버에서 직접 생성하므로 기본 생성자 불필요
- Request DTO에 `@Builder` 불필요 — 클라이언트가 JSON으로 전달

---

## 상수 관리 (common-core)

Kafka 토픽명, 헤더 키 등 상수는 interface로 묶어 관리한다.

```java
// common-core: KafkaTopics.java
public interface KafkaTopics {
    String ROUTINE_EXECUTION_COMPLETED    = "routine.execution.completed";
    String ROUTINE_NOTIFICATION_SCHEDULED = "routine.notification.scheduled";
    String CHALLENGE_MEMBER_JOINED        = "challenge.member.joined";
    String CHALLENGE_MEMBER_LEFT          = "challenge.member.left";
    String CHAT_MESSAGE_CREATED           = "chat.message.created";
}

// common-core: HeaderConstants.java
public interface HeaderConstants {
    String USER_ID        = "X-User-Id";
    String GATEWAY_SECRET = "X-Gateway-Secret";
}
```
