# ADR-0019: 마이크로서비스 내부 보안 전략 — 네트워크 격리 + Gateway Secret 헤더

## Status
Accepted

---

## Context

Routinely는 MSA 구조로 5개의 마이크로서비스가 Spring Cloud Gateway를 통해 외부 요청을 수신한다.

Gateway는 JWT를 검증한 후 `X-User-Id` 헤더를 추가하여 내부 서비스로 요청을 전달한다.
이때 각 마이크로서비스는 다음 문제를 해결해야 한다:

> "이 요청이 Gateway를 통해 인증된 요청인가, 아니면 누군가 서비스에 직접 접근한 요청인가?"

`X-User-Id` 헤더는 Gateway가 추가하는 신뢰 정보이므로,
이 헤더를 외부에서 임의로 주입할 수 있다면 인증 우회가 가능해진다.

따라서 Gateway를 통하지 않은 직접 요청을 차단할 전략이 필요하다.

---

## Decision

마이크로서비스 내부 보안은 **두 계층의 방어를 조합**하여 적용한다.

1. **Docker 네트워크 격리** (1차 방어 — 인프라 레벨)
2. **X-Gateway-Secret 헤더 필터** (2차 방어 — 애플리케이션 레벨)

**각 마이크로서비스에 Spring Security를 도입하지 않는다.**

**user-service 포함 전 서비스에 적용된다.**
user-service는 로그인/회원가입 엔드포인트를 제공하지만, Spring Security 필터 체인을 사용하지 않고 일반 컨트롤러로 구현한다.
비밀번호 해시는 `spring-security-crypto` 의존성의 `BCryptPasswordEncoder`만 사용한다.

---

## Options Considered

### Option 1: IP 기반 접근 제어 + Spring Security (기각)

각 서비스에 Spring Security를 설정하고, Gateway의 IP만 허용하는 방식.

```java
.requestMatchers("/**").access(
    "hasIpAddress('127.0.0.1') or hasIpAddress('" + gatewayIp + "')"
)
```

**기각 이유:**

| 문제 | 설명 |
|------|------|
| Spring Security 남용 | IP 체크 하나를 위해 Security 전체 스택 부팅 |
| IP 스푸핑 취약 | 같은 네트워크 내 다른 서비스도 동일 IP 사용 가능 |
| 환경 의존성 | Gateway IP를 각 서비스 설정에 주입해야 함 — IP 변경 시 전체 수정 |
| 보안 미흡 | IP가 같다고 해서 "Gateway가 인증한 요청"임을 보장하지 않음 |

---

### Option 2: Docker 네트워크 격리 + X-Gateway-Secret 헤더 (채택)

**1차: 네트워크 격리 (인프라 레벨)**

서비스들을 Docker 내부 네트워크에만 배치하여 외부에서 물리적으로 접근 불가능하게 한다.
Gateway만 퍼블릭 포트를 노출한다.

**2차: X-Gateway-Secret 헤더 (앱 레벨)**

Gateway가 모든 내부 전달 요청에 공유 시크릿 헤더를 추가한다.
각 서비스는 `OncePerRequestFilter` 하나로 이 헤더를 검증한다.
Spring Security 스택은 사용하지 않는다.

---

## Implementation

### Docker Compose — 네트워크 격리

```yaml
services:
  gateway:
    ports:
      - "8080:8080"   # 외부에 노출되는 유일한 서비스
    networks:
      - public
      - internal

  user-service:
    # ports 없음 — 외부 접근 불가
    networks:
      - internal

  challenge-service:
    networks:
      - internal

  routine-service:
    networks:
      - internal

  chat-service:
    networks:
      - internal

  notification-service:
    networks:
      - internal

networks:
  public:
  internal:
    internal: true   # 외부 인터넷 차단
```

---

### Gateway — Secret 헤더 추가

```java
// GatewayJwtFilter.java
ServerWebExchange mutatedExchange = exchange.mutate()
    .request(req -> req
        .header("X-User-Id", userId)
        .header("X-Gateway-Secret", gatewaySecret)  // 추가
    )
    .build();
```

```yaml
# gateway application.yml
gateway:
  secret: ${GATEWAY_SECRET}
```

---

### user-service — 로그인 구현

Spring Security의 `UsernamePasswordAuthenticationFilter`를 사용하지 않고 일반 컨트롤러로 구현한다.

```java
// Spring Security 전체 스택 없이 구현
@PostMapping("/api/v1/auth/login")
public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
    User user = userRepository.findByEmail(request.getEmail())
        .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS, ...));

    if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
        throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, ...);
    }

    String accessToken  = jwtProvider.createAccessToken(user.getId(), user.getRole());
    String refreshToken = jwtProvider.createRefreshToken(user.getId());

    return ApiResponse.ok("로그인에 성공했습니다.", new LoginResponse(accessToken, refreshToken));
}
```

비밀번호 해시는 `spring-boot-starter-security` 대신 `spring-security-crypto`만 의존한다.

```java
// BCrypt Bean만 등록. SecurityFilterChain 설정 없음
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
}
```

---

### 각 마이크로서비스 — GatewayAuthFilter

Spring Security 대신 단순 Filter 하나로 검증한다.

```java
@Component
@RequiredArgsConstructor
public class GatewayAuthFilter extends OncePerRequestFilter {

    @Value("${gateway.secret}")
    private String gatewaySecret;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {
        String secret = request.getHeader("X-Gateway-Secret");
        if (!gatewaySecret.equals(secret)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Direct access not allowed");
            return;
        }
        chain.doFilter(request, response);
    }
}
```

```yaml
# 각 서비스 application.yml
gateway:
  secret: ${GATEWAY_SECRET}   # 환경변수로 관리. Gateway와 동일한 값 공유
```

---

### 환경변수 관리

`GATEWAY_SECRET`은 코드에 하드코딩하지 않고 환경변수로 관리한다.

```bash
# .env (git에서 제외)
GATEWAY_SECRET=your-strong-random-secret-here
```

```yaml
# docker-compose.yml
services:
  gateway:
    environment:
      GATEWAY_SECRET: ${GATEWAY_SECRET}
  user-service:
    environment:
      GATEWAY_SECRET: ${GATEWAY_SECRET}
  # ... 각 서비스 동일
```

---

### 환경별 시크릿 주입 전략

| 환경 | 주입 방법 |
|------|-----------|
| IntelliJ 직접 실행 | Run Configuration → Environment Variables에 `GATEWAY_SECRET` 설정 |
| 로컬 docker-compose | 프로젝트 루트 `.env` 파일 (`GATEWAY_SECRET=...`) |
| AWS 배포 | Secrets Manager 또는 SSM Parameter Store → ECS Task Definition secrets 참조 |

`.env` 파일은 반드시 `.gitignore`에 추가한다. `.env.example`을 커밋하여 팀원이 참고할 수 있도록 한다.

---

### 로컬 개발 — GatewayAuthFilter 비활성화

서비스 단독 개발/테스트 시 Gateway 없이 직접 호출하면 `X-Gateway-Secret` 헤더가 없어 전부 401이 된다.
`local` 프로파일에서 필터를 Bean으로 등록하지 않아 우회한다.

```java
@Component
@Profile("!local")  // local 프로파일에서는 Bean 미등록
public class GatewayAuthFilter extends OncePerRequestFilter {
    // ...
}
```

```yaml
# application-local.yml
spring:
  profiles:
    active: local

gateway:
  secret: ${GATEWAY_SECRET:local-secret}  # 기본값으로 환경변수 없어도 기동 가능
```

IntelliJ Run Configuration에서 `spring.profiles.active=local` 설정 시 필터 없이 자유롭게 테스트 가능하다.

---

## 요청 흐름

```
[ 외부 클라이언트 ]
        │ HTTPS :8080
        ▼
[ Gateway ]
  ├── JWT 검증
  ├── X-User-Id 헤더 추가
  ├── X-Gateway-Secret 헤더 추가
  └── 내부 네트워크로 라우팅
        │ Docker internal network
        ▼
[ 각 마이크로서비스 ]
  └── GatewayAuthFilter
        ├── X-Gateway-Secret 검증 통과 → 요청 처리
        └── 검증 실패 → 401 응답
```

---

## 방어 계층 요약

| 계층 | 방식 | 차단 대상 |
|------|------|-----------|
| 인프라 (1차) | Docker 내부 네트워크 | 인터넷에서 서비스 직접 접근 |
| 애플리케이션 (2차) | X-Gateway-Secret 헤더 | 동일 네트워크 내 무단 서비스 직접 호출 |

---

## Consequences

### Positive

- Spring Security를 각 서비스에서 제거하여 부팅 속도 및 코드 단순성 향상
- 네트워크 격리만으로 대부분의 공격 벡터 차단
- 시크릿 헤더로 앱 레벨 이중 방어 구성
- 환경변수 하나(`GATEWAY_SECRET`)만 관리하면 됨

### Negative

- `GATEWAY_SECRET` 유출 시 내부 서비스 직접 호출 가능
  - 완화: 정기적인 시크릿 로테이션, Vault 등 시크릿 관리 도구 도입 가능
- 로컬에서 서비스 단독 실행 시 네트워크 격리 없음
  - 완화: `local` 프로파일에서 `GatewayAuthFilter` 비활성화하여 개발 편의성 확보

---

## Architectural Principle

> 마이크로서비스의 내부 보안은 Spring Security가 아닌
> 네트워크 격리와 단순한 헤더 필터로 충분하다.
>
> Spring Security는 인증/인가 로직이 필요한 곳(Gateway)에만 집중한다.
