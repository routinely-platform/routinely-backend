# 서비스 내부 계층 설계 (Pragmatic Clean Architecture)

## 채택 배경

마이크로서비스는 서비스 자체가 이미 하나의 Bounded Context다.  
엄격한 포트/어댑터 분리(Hexagonal Architecture)는 이 규모에서 과도한 보일러플레이트를 만든다.  
따라서 **의존성 방향의 핵심 규칙은 유지하되, 불필요한 추상화는 제거한** Pragmatic 방식을 채택한다.

---

## 4계층 구조

### domain — "무엇이 가능하고 불가능한가"

핵심 비즈니스 규칙이 존재하는 계층이다.  
**JPA `@Entity`를 domain에 직접 선언한다** (풀 클린 아키텍처의 OrderEntity 분리 방식을 채택하지 않는다).

이유: 이 프로젝트에서 DB 교체 가능성이 없고, 도메인 모델과 DB 스키마가 1:1 대응이기 때문이다.

포함 대상:
- `@Entity` 클래스 (JPA 어노테이션 포함)
- `enum` (상태, 역할 등)
- Repository `interface` (`extends JpaRepository` — 구현체 분리 없음)

```java
// domain/User.java
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    // 상태 변경은 의미 있는 메서드로
    public void deactivate() {
        this.isActive = false;
    }
}

// domain/UserRepository.java
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
}
```

---

### application — "어떤 순서로 실행하는가"

도메인 객체를 조합해 유스케이스를 완성하는 계층이다.  
비즈니스 판단은 도메인에 위임하고, 자신은 흐름만 조율한다.

포함 대상:
- `Service` 클래스 (`@Service`)
- `Command` DTO — presentation → application 입력
- `Result` DTO — application → presentation 출력

```java
// application/service/UserService.java
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserResult signUp(SignUpCommand command) {
        // TODO(human): 이메일 중복 검사 후 User 생성 및 저장
        // HINT: passwordEncoder.encode(), userRepository.save()
    }

    public TokenResult login(LoginCommand command) {
        // TODO(human): 이메일로 사용자 조회 → 비밀번호 검증 → JWT 발급
        // HINT: passwordEncoder.matches(), jwtTokenProvider.generateToken()
    }
}

// application/dto/SignUpCommand.java
@Getter
@Builder
public class SignUpCommand {
    private String email;
    private String password;
    private String nickname;
}

// application/dto/UserResult.java
@Getter
@Builder
public class UserResult {
    private Long   userId;
    private String email;
    private String nickname;
    private String role;

    public static UserResult from(User user) {
        return UserResult.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .role(user.getRole().name())
                .build();
    }
}
```

---

### infrastructure — "기술적으로 어떻게 구현하는가"

모든 기술적 세부사항이 격리되는 계층이다.  
라이브러리·프레임워크를 교체할 때 이 계층만 수정한다.

포함 대상:
- JWT 생성·검증 (`jwt/`)
- Kafka Producer / Consumer (`messaging/producer/`, `messaging/consumer/`)
- Outbox 엔티티 (`messaging/outbox/`)
- gRPC 클라이언트 (`grpc/client/`) — 다른 서비스 호출
- gRPC 서버 구현체 (`grpc/server/`) — gRPC 엔드포인트 노출
- Spring 설정 클래스 (`config/`)

```java
// infrastructure/jwt/JwtTokenProvider.java
@Component
public class JwtTokenProvider {

    public String generateToken(Long userId, String role) { ... }
    public Long extractUserId(String token) { ... }
}

// infrastructure/grpc/client/UserGrpcClient.java — 다른 서비스에서 user 정보 조회 시
@Component
@RequiredArgsConstructor
public class UserGrpcClient {
    private final UserServiceGrpc.UserServiceBlockingStub stub;

    public UserInfo getUser(Long userId) { ... }
}
```

---

### presentation — "외부와 어떻게 소통하는가"

외부 요청을 받아 application이 이해하는 Command로 변환하고,  
application이 반환한 Result를 외부 응답으로 변환하는 계층이다.  
**비즈니스 로직은 한 줄도 없다.**

포함 대상:
- `Controller` (`@RestController`)
- `Request` DTO — HTTP 요청 입력 (유효성 검사 포함)
- `Response` DTO — HTTP 응답 출력

```java
// presentation/rest/UserController.java
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<UserProfileResponse>> signUp(
            @RequestBody @Valid SignUpRequest request) {

        SignUpCommand command = request.toCommand();          // Request → Command 변환
        UserResult result = userService.signUp(command);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("회원가입에 성공했습니다.",
                        UserProfileResponse.from(result)));  // Result → Response 변환
    }
}

// presentation/rest/dto/request/SignUpRequest.java
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SignUpRequest {

    @NotBlank(message = "이메일은 필수입니다.")
    @Email(message = "올바른 이메일 형식이어야 합니다.")
    private String email;

    @NotBlank(message = "비밀번호는 필수입니다.")
    @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
    private String password;

    @NotBlank(message = "닉네임은 필수입니다.")
    @Size(min = 2, max = 20, message = "닉네임은 2~20자여야 합니다.")
    private String nickname;

    public SignUpCommand toCommand() {            // 아는 쪽(presentation)이 변환 책임
        return SignUpCommand.builder()
                .email(email)
                .password(password)
                .nickname(nickname)
                .build();
    }
}

// presentation/rest/dto/response/UserProfileResponse.java
@Getter
@Builder
public class UserProfileResponse {
    private Long   userId;
    private String email;
    private String nickname;

    public static UserProfileResponse from(UserResult result) {
        return UserProfileResponse.builder()
                .userId(result.getUserId())
                .email(result.getEmail())
                .nickname(result.getNickname())
                .build();
    }
}
```

---

## 의존성 규칙

```
presentation ──→ application ──→ domain ←── infrastructure
```

| 나 \ 상대 | domain | application | infrastructure | presentation |
|---|:---:|:---:|:---:|:---:|
| **domain** | — | X | X | X |
| **application** | O | — | X | X |
| **infrastructure** | O | O | — | X |
| **presentation** | O | O | X | — |

- `domain`은 아무것도 import하지 않는다
- `application`은 `domain`만 안다 (infrastructure의 구체 타입을 직접 import하지 않는다)
- `infrastructure`와 `presentation`은 서로를 모른다

> **주의:** application이 JwtTokenProvider를 직접 생성자 주입받는 것은 허용한다.  
> 엄격한 포트/어댑터 분리(JwtPort 인터페이스 선언)는 이 프로젝트에서 채택하지 않는다.

---

## DTO 변환 흐름

```
[클라이언트]
      │
      ▼
 SignUpRequest              ← presentation/rest/dto/request/
      │  request.toCommand()    ← Controller에서 변환 (아는 쪽이 책임)
      ▼
 SignUpCommand              ← application/dto/
      │  UserService 처리
      ▼
 UserResult                 ← application/dto/
      │  UserProfileResponse.from(result)  ← Controller에서 변환
      ▼
 UserProfileResponse        ← presentation/rest/dto/response/
      │
      ▼
[클라이언트]
```

**변환 원칙: 아는 쪽이 변환 책임을 진다.**

| 변환 방향 | 변환 메서드 위치 | 이유 |
|---|---|---|
| Request → Command | `Request.toCommand()` | presentation이 application을 알기 때문 |
| Result → Response | `Response.from(result)` | presentation이 application을 알기 때문 |
| Entity → Result | `Result.from(entity)` | application이 domain을 알기 때문 |

---

## DTO 네이밍 규칙

| DTO 종류 | 위치 | 역할 |
|---|---|---|
| `XxxRequest` | `presentation/rest/dto/request/` | HTTP 요청 수신, `@Valid` 검사 |
| `XxxResponse` | `presentation/rest/dto/response/` | HTTP 응답 반환 |
| `XxxCommand` | `application/dto/` | Service 입력 |
| `XxxResult` | `application/dto/` | Service 출력 |

---

## 서비스별 계층 차이

### chat-service — presentation이 websocket

REST 컨트롤러 없이 WebSocket 핸들러가 외부 진입점이 된다.

```
presentation/
└── websocket/
    ├── ChatMessageHandler.java    # @MessageMapping
    └── dto/
        ├── ChatMessageRequest.java
        └── ChatMessageResponse.java
```

### notification-service — presentation 없음

외부 HTTP 요청을 받지 않는다. 스케줄러와 PGMQ 워커가 트리거 역할을 한다.

```
infrastructure/
├── pgmq/
│   └── NotificationWorker.java    # PGMQ dequeue → 알림 발송
└── scheduler/
    └── NotificationScheduler.java # 주기적 due 체크
```

---

## 자주 하는 실수

**Request DTO를 Service에 직접 넘기는 것.**  
`userService.signUp(request)` 형태는 presentation과 application이 결합된다.  
Controller에서 `request.toCommand()`로 변환한 뒤 전달한다.

**application DTO를 Controller에서 직접 반환하는 것.**  
`UserResult`를 그대로 반환하면 API 스펙 변경 시 application 계층까지 영향이 퍼진다.  
`UserProfileResponse.from(result)`로 변환해서 반환한다.

**Service에서 비즈니스 판단을 직접 코딩하는 것.**  
`if (user.getIsActive() == false)` 같은 조건문이 Service에 있으면 규칙이 흩어진다.  
`user.deactivate()`, `user.isActivated()` 같이 도메인 객체에 위임한다.
