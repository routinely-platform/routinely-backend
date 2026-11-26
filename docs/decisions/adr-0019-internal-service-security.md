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

### Gateway — 신뢰 헤더 발급 경계 (#154)

`X-User-Id` · `X-Gateway-Secret`(`HeaderConstants`의 두 값)은 **게이트웨이만 발급한다.**
클라이언트가 같은 이름으로 보낸 값은 게이트웨이 진입 시점에 버린다.

처음에는 이 경계가 코드로 보장되지 않았다. 인증 경로는 `JwtAuthenticationFilter`가 `.header()`(덮어쓰기)로 두 값을 넣어
우연히 안전했지만, **공개 경로**(`signup` · `login` · `refresh` · `logout`)는 시크릿만 더하고 클라이언트의 `X-User-Id`를 그대로 두었다.
`RateLimitFilter`는 `X-User-Id`가 있으면 그 값을 카운터 키로 쓰므로, 값을 바꿔 가며 보내면 요청마다 새 버킷이 생겨
IP 한도(기본 60초 30회)를 우회했다. 남의 userId로 그 사용자의 버킷을 소진시킬 수도 있었다.

그래서 정화를 인증 필터에서 떼어 **전용 글로벌 필터**로 둔다.

```java
@Component   // 프로파일 조건 없음 — 항상 켠다
public class TrustedHeaderSanitizationFilter implements GlobalFilter, Ordered {
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest sanitized = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(HeaderConstants.USER_ID);
                    headers.remove(HeaderConstants.GATEWAY_SECRET);
                })
                .build();
        return chain.filter(exchange.mutate().request(sanitized).build());
    }
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
}
```

#### 게이트웨이 글로벌 필터 순서

| order | 필터 | 신뢰 헤더에 하는 일 |
|---|---|---|
| `HIGHEST_PRECEDENCE` | `TrustedHeaderSanitizationFilter` | 클라이언트가 보낸 두 헤더를 지운다(대소문자 · 값 여러 개 포함) |
| 0 | `JwtAuthenticationFilter` (모든 프로파일, #201) | 인증 경로: JWT의 userId와 시크릿을 넣는다 / 공개 경로: 시크릿만 넣는다 |
| 1 | `RateLimitFilter` | `X-User-Id`가 있으면 `rate_limit:{userId}`, 없으면 `rate_limit:ip:{IP}` |
| 1~ | 라우트 필터 (`CircuitBreaker` 등) | — |

`RateLimitFilter`가 읽는 `X-User-Id`는 이제 항상 게이트웨이가 넣은 값이거나 없다. 공개 경로는 전부 IP 키로 집계된다.
그래서 IP 해석(`X-Forwarded-For` 처리)의 정확성이 곧 가용성에 걸린다 — #156. 신뢰 경계와 배포 전제는 [ADR-0003 "클라이언트 IP 신뢰 경계"](adr-0003-nginx-gateway-structure.md#클라이언트-ip-신뢰-경계-156)에 있다.

#### 채택하지 않은 방법

| 방법 | 이유 |
|---|---|
| `JwtAuthenticationFilter` 진입부에서 지우기 | 동작은 하지만 인증 정책과 헤더 위생이 한 클래스에 섞인다. (당시 인증 필터는 `local`에서 꺼져 정화도 같이 꺼졌다 — #201에서 local도 켰다) |
| 라우트 `RemoveRequestHeader=X-User-Id` | 라우트 필터는 order 1부터라 `RateLimitFilter`(1)보다 늦어 1차 방어가 못 된다. 2차 방어로 더해도 `JwtAuthenticationFilter` **뒤에** 돌아 인증 경로의 정상 `X-User-Id`까지 지운다 |
| 정화 필터에 `@Profile("!local")` | #201에서 local도 두 인증 필터를 켜기로 해 꺼 둘 이유가 사라졌다. 켜 두면 local 게이트웨이도 운영과 같은 경계를 지킨다 |

#### 검증

| 테스트 | 확인하는 것 |
|---|---|
| `TrustedHeaderSanitizationFilterTest` | 두 헤더 제거 · 다른 헤더 보존 · 헤더명 대소문자 · 값 여러 개 · order = `HIGHEST_PRECEDENCE` |
| `JwtAuthenticationFilterTest` | 정화 → 인증 순으로 이었을 때 공개 경로의 위조 `X-User-Id`가 전달되지 않고, 인증 경로는 JWT 값으로 바뀐다 |
| `TrustedHeaderRateLimitIntegrationTest` | 실제 컨텍스트(`test`) · Testcontainers Redis로 `X-User-Id`를 매번 바꿔 보내도 31번째가 429, 키는 `rate_limit:ip:*` 하나, 업스트림에는 `X-User-Id`가 없다 |

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

// @Component 없음 — 등록은 위 @Import가 맡는다. 프로파일 조건도 없다(#201 — local에서도 켠다)
public class GatewayAuthFilter extends OncePerRequestFilter { ... }
```

- 서비스별 활성화 속성(`enabled`)은 두지 않는다 — 방어가 꺼지는 경로가 늘어난다
- `gateway.secret`이 **없거나 비어 있으면 local을 포함해 기동이 실패한다.** 시크릿이 없을 때 필터를 생략하는 조건은 두지 않는다
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

# 각 서비스 application-test.yaml — 필터가 모든 프로파일에서 등록되므로 테스트에도 필요하다
gateway:
  secret: test-gateway-secret
```

#### 검증

| 테스트 | 확인하는 것 |
|---|---|
| `common-web` `WebAutoConfigurationTest` | 기본 · `test` · `prod` · `local` 계열 → 모두 등록 / 시크릿 없음 · 빈 값 → `prod` · `local` 모두 기동 실패 |
| `common-web` `GatewayAuthFilterIntegrationTest` | 자동설정이 등록한 필터로 헤더 없음 · 틀린 값 401, 맞는 값 통과, `/actuator` 통과 |
| `common-web` `GatewayAuthFilterTomcatPathTest` | **내장 Tomcat에 원시 요청 줄**로 `/actuator/../` · `%2e%2e` · `;` · 이중 슬래시 · 대소문자 · 인코딩된 슬래시를 보내도 보호 경로가 400(Tomcat 거부) 또는 401(필터 차단)이다. MockMvc는 컨테이너의 경로 정규화를 거치지 않아 이 경우를 검증하지 못한다 |
| 서비스 `GatewayAuthFilterRegistrationTest` (5개) | 실제 서비스 컨텍스트(`test`)에 필터가 있다 |
| routine · challenge `GatewayAuthFilterLocalProfileTest` | 실제 서비스 컨텍스트(`local,test`)에도 필터가 있다(#201) |

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

### 로컬 개발 — local에서도 두 인증 필터를 켠다 (#201)

**결정 (2026-11-14)**: 게이트웨이 `JwtAuthenticationFilter`와 서비스 `GatewayAuthFilter`에서 `@Profile("!local")`을 없앴다.
local도 운영과 같은 순서로 돈다 — 정화(#154) → JWT 검증 · `X-User-Id` · 시크릿 주입 → Rate Limit.

| | 전 (`!local`) | 후 (#201) |
|---|---|---|
| 게이트웨이 경유 보호 API | 토큰을 보지 않아 `X-User-Id`가 비어 400. 정화(#154) 뒤라 손으로 넣은 값도 지워졌다 | 토큰의 `id`가 `X-User-Id`로 들어가 정상 호출. 위조 값은 덮어쓴다 |
| 서비스 직접 호출 (IDE · curl) | 헤더 없이 통과 | `X-Gateway-Secret: local-secret`이 없으면 401 |
| Swagger UI의 login → Authorize → 보호 API | 동작하지 않았다 | 동작한다 |

**배경** — 두 필터는 짝이라 local에서 한쪽만 켜면 게이트웨이 경유 호출이 전부 401이 된다. 둘을 끈 상태에서는 local로 인증 흐름을
확인할 수 없어, 실제로는 local 테스트 때마다 두 필터를 직접 켜고 쓰고 있었다. 그 사용 방식을 코드로 옮겼다.

**대가** — 서비스를 직접 호출할 때 시크릿을 붙여야 한다. 값은 게이트웨이와 모든 서비스의 local 설정에 같은 기본값으로 들어 있다.

```bash
curl localhost:8082/api/v1/routines -H 'X-Gateway-Secret: local-secret' -H 'X-User-Id: 1'
```

```yaml
# application-local.yaml (gateway · user · routine · challenge · chat · notification)
gateway:
  secret: ${GATEWAY_SECRET:local-secret}
```

- chat · notification에는 local 설정 파일이 없어, 필터를 켜면 `GATEWAY_SECRET` 없이 띄울 때 "빈 시크릿"으로 기동이 실패했다. 같은 기본값을 담은 `application-local.yaml`을 두었다
- **`local`과 `prod`를 함께 켜면 기동이 실패한다(#215).** local 전용 구성(문서 라우트 · Swagger · gRPC reflection · 시크릿 기본값)이 prod에 섞이기 때문이다. `libs/common-boot`의 `ProfileCombinationGuard`가 서비스 컨텍스트 생성 전에 막고(Spring Cloud bootstrap은 쓰지 않는다 — 켜면 입력 경로에 따라 부모 컨텍스트가 먼저 생길 수 있다), 서비스마다 `ProfileCombinationGuardRegistrationTest`가 등록을 확인한다
- local 서비스 포트는 개발자 머신 밖으로 노출하지 않는다는 전제다. 시크릿 기본값이 공개 저장소에 있기 때문이다

---

## 요청 흐름

```
[ 외부 클라이언트 ]
        │ HTTPS :8080
        ▼
[ Gateway ]
  ├── 클라이언트가 보낸 X-User-Id · X-Gateway-Secret 제거
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
  - 완화: local 서비스 포트를 개발자 머신 밖으로 노출하지 않는다(위 "로컬 개발 — local에서도 두 인증 필터를 켠다" 절의 전제). local 시크릿 기본값(`local-secret`)은 직접 호출용이지 보호 수단이 아니다 — 공개 저장소에 있다

---

## Architectural Principle

> 마이크로서비스의 내부 보안은 Spring Security가 아닌
> 네트워크 격리와 단순한 헤더 필터로 충분하다.
>
> Spring Security는 인증/인가 로직이 필요한 곳(Gateway)에만 집중한다.
