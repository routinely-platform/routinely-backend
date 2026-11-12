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

    String accessToken  = jwtProvider.generateAccessToken(user.getId(), user.getRole());
    String refreshToken = refreshTokenStore.save(user.getId());  // opaque UUID, Redis에 TTL 7일로 저장

    ResponseCookie cookie = ResponseCookie.from("refresh_token", refreshToken)
        .httpOnly(true).secure(true).sameSite("Strict").path("/api/v1/auth").build();

    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(ApiResponse.ok("로그인에 성공했습니다.", new LoginResponse(accessToken)));
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

Spring Security 대신 단순 Filter 하나로 검증한다. 구현은 `libs/common-web`의 `GatewayAuthFilter`.

#### 등록 경로 — 컴포넌트 스캔이 아니라 자동설정 (#208)

필터는 `com.routinely.web.filter`에 있고, 각 서비스의 컴포넌트 스캔은 `com.routinely.{svc}_service`에서 시작한다.
**스캔 범위 밖이라 `@Component`를 붙여도 등록되지 않는다.** 처음 구현(#6)이 이 상태였고, 필터를 `new`로 만들어
로직만 보는 단위 테스트로는 누락이 드러나지 않았다(#208에서 발견).

그래서 `common-web`의 자동설정 목록에 직접 적는다. 서비스는 `implementation project(':libs:common-web')` 한 줄로
jar 안의 `META-INF/spring/...AutoConfiguration.imports`를 받고, Boot가 기동할 때 이 파일을 읽어 `WebAutoConfiguration`을 로드한다.

```java
@AutoConfiguration
@Import({GlobalExceptionHandler.class, GatewayAuthFilter.class})
public class WebAutoConfiguration { }

@Profile("!local")          // @Component 없음 — 등록은 위 @Import가 맡는다
public class GatewayAuthFilter extends OncePerRequestFilter { ... }
```

- 서비스별 활성화 속성(`enabled`)은 두지 않는다 — 방어가 꺼지는 경로가 늘어난다
- `!local`에서 `gateway.secret`이 **없거나 비어 있으면 기동이 실패한다.** 시크릿이 없을 때 필터를 생략하는 조건은 두지 않는다
- 순서는 지정하지 않는다(기본 = 가장 나중). Boot의 관측 필터가 먼저 돌아 401에도 traceId · 메트릭이 남는다

#### 적용 범위

| 대상 | 필터 | 이유 |
|---|:---:|---|
| 서비스 HTTP 포트의 모든 경로 | ✅ | `X-User-Id`를 믿는 컨트롤러 전부 |
| `/actuator` · `/actuator/**` | ❌ 제외 | Prometheus가 서비스 포트에 헤더 없이 직접 수집한다. 정규화한 경로로 판정해 `/actuator/../api/...`는 제외되지 않는다. **management 포트를 분리하면(#23) 이 제외를 지운다** |
| gRPC (HTTP 포트 + 1000) | ❌ 대상 아님 | 서블릿 필터 체인을 지나지 않는 별도 서버다. 서비스 간 내부 호출이라 1차 방어(내부 네트워크)에 기댄다 |
| gateway-service | ❌ 대상 아님 | 리액티브(WebFlux)이고 `common-web`을 의존하지 않는다. 시크릿을 **붙이는** 쪽이다 |

```yaml
# 각 서비스 application.yaml
gateway:
  secret: ${GATEWAY_SECRET}   # 환경변수로 관리. Gateway와 동일한 값 공유

# 각 서비스 application-test.yaml — test는 !local이라 필터가 등록된다
gateway:
  secret: test-gateway-secret
```

#### 검증

| 테스트 | 확인하는 것 |
|---|---|
| `common-web` `WebAutoConfigurationTest` | 기본 · `test` · `prod` → 등록 / `local` 계열 → 미등록 / 시크릿 없음 · 빈 값 → 기동 실패 |
| `common-web` `GatewayAuthFilterIntegrationTest` | 자동설정이 등록한 필터로 헤더 없음 · 틀린 값 401, 맞는 값 통과, `/actuator` 통과 |
| `common-web` `GatewayAuthFilterTomcatPathTest` | **내장 Tomcat에 원시 요청 줄**로 `/actuator/../` · `%2e%2e` · `;` · 이중 슬래시 · 대소문자 · 인코딩된 슬래시를 보내도 보호 경로가 400(Tomcat 거부) 또는 401(필터 차단)이다. MockMvc는 컨테이너의 경로 정규화를 거치지 않아 이 경우를 검증하지 못한다 |
| 서비스 `GatewayAuthFilterRegistrationTest` (5개) | 실제 서비스 컨텍스트(`test`)에 필터가 있다 |
| routine · challenge `GatewayAuthFilterLocalProfileTest` | 실제 서비스 컨텍스트(`local,test`)에 필터가 없다 |

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
`local` 프로파일에서는 필터를 Bean으로 등록하지 않는다(`@Profile("!local")`).

Gateway의 `JwtAuthenticationFilter`도 `@Profile("!local")`이라 **local에서는 시크릿을 붙이지 않는다.**
두 필터는 짝이라 한쪽만 켜면 local에서 Gateway 경유 호출이 전부 401이 된다.
local에서 두 필터를 켤지는 **#201(Swagger UI)에서 함께 재검토한다.**

```yaml
# application-local.yaml (user · routine · challenge) — 필터가 없으므로 값은 쓰이지 않는다
gateway:
  secret: ${GATEWAY_SECRET:local-secret}
```

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
