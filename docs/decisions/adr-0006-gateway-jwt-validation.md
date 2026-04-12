# ADR-0006: API Gateway에서 JWT 직접 검증

- Status: Accepted
- Date: 2026-02-15
- Context: Routinely (MSA, Spring Cloud Gateway, User Service, JWT)

## Context

Routinely는 API Gateway를 진입점으로 여러 마이크로서비스(User, Challenge, Routine, Notification, Chat, Report)로 라우팅하는 MSA 구조이다.  
클라이언트 요청에 대한 인증(JWT 검증)을 어디에서 수행할지 결정해야 한다.

현재 고려되는 인증 처리 방식은 다음 두 가지이다.

### Option A: Gateway 직접 검증

```
Client ──> Gateway(JWT 파싱/검증) ──> Target Service
```

### Option B: User Service 위임 검증

```
Client ──> Gateway ──> User Service(검증) ──> Gateway ──> Target Service
```

프로젝트의 기존 구현 예시(위임 검증)는 `/validate` API를 통해 토큰 파싱 후 DB에서 사용자 조회까지 수행한다.

```java
@PostMapping("/validate")
public ResponseEntity<User> validateToken(@RequestBody TokenRequest tokenRequest) {
    Claims claims = jwtService.parseJwtClaims(tokenRequest.getToken());
    return ResponseEntity.ok(userService.getUserByEmail(claims.getSubject()).orElseThrow());
}
```

## Decision

API Gateway에서 JWT를 직접 검증한다.

User Service는 인증 토큰의 발급/갱신(로그인, 리프레시 등) 역할을 담당하며, Gateway는 요청 처리 단계에서 토큰 파싱/서명검증/만료검사를 수행한다.

Gateway는 검증된 토큰의 claims에서 필요한 값을 추출하여, 다운스트림 서비스로 전달한다(예: X-USER-ID, X-USER-ROLE).

## Rationale

Gateway 직접 검증을 선택한 근거는 다음과 같다.

### 1) 네트워크 홉 감소

위임 방식은 모든 요청마다 User Service로 추가 HTTP 호출이 발생한다.

- 위임 방식: 요청 1건 = User Service 호출 1건 추가
- 직접 검증: 요청 1건 = 추가 호출 0건

트래픽 증가 시 네트워크 비용이 누적되고, Gateway↔UserService 호출 자체가 병목이 될 수 있다.

### 2) 장애 전파(SPOF) 감소

위임 방식은 User Service 장애가 곧 인증 경로 장애로 이어져 전체 서비스가 영향을 받는다.

- User Service 장애 → 인증 필요한 모든 요청 실패 가능

JWT는 토큰 자체에 검증에 필요한 정보가 포함되어 "발급자에게 매번 물어보지 않아도 되는(stateless)" 설계를 전제한다.  
Gateway 직접 검증은 이 철학에 더 부합하며, 인증 경로의 단일 장애점을 줄인다.

### 3) 지연 시간 감소

- 직접 검증: JWT 파싱/검증은 CPU 연산 중심(대략 ~1ms 수준)
- 위임 검증: 네트워크 왕복 + (종종) DB 조회가 포함(대략 ~10–50ms+)

요청 지연과 꼬리 지연(p99) 관점에서도 Gateway 직접 검증이 유리하다.

## Consequences

### Positive

- 인증 요청당 추가 네트워크 호출 제거 → 성능 개선, 비용 감소
- User Service 장애가 전체 인증 실패로 확산되는 위험 완화
- JWT의 stateless 특성을 활용한 단순한 경로 구성

### Negative / Trade-offs

- 실시간 사용자 차단(계정 정지/권한 회수 등)이 기본적으로 어렵다(토큰 만료까지 유효).
- 토큰에 포함된 claims는 발급 시점 정보이며, 최신 사용자 정보 반영에 한계가 있다.
- Gateway에 인증 로직이 집중되므로 필터/보안 설정이 복잡해질 수 있다.

## Mitigations

실시간 차단 요구가 발생할 경우, 다음 전략을 사용한다.

> **MVP 이후 고려사항**: 아래 전략은 MVP 범위에서 구현하지 않는다.
> 계정 정지/강제 로그아웃 등 실시간 차단 요구가 생기는 시점에 도입한다.

- Gateway 직접 검증 + Redis 기반 블랙리스트(또는 denylist)
- 로그아웃/강제 만료/계정 정지 시 해당 토큰(jti) 또는 사용자 ID를 Redis에 저장
- Gateway 필터에서 JWT 검증 후 Redis 조회로 차단 여부 확인

또한 토큰에 필요한 정보를 claims에 포함하여 "매 요청마다 User Service 조회" 필요를 낮춘다.

## Implementation Sketch

### 공개 경로 구분 전략

Gateway 필터에서 JWT 검증 전, 해당 요청이 인증이 필요한 요청인지 판별해야 한다.

**채택 방식: 공개 경로 화이트리스트**

공개 API(인증 불필요)를 명시적으로 목록으로 정의하고, 목록에 없는 모든 요청은 JWT 검증을 수행한다.

**검토한 방식**

| 방식 | 설명 | 채택 여부 |
|---|---|---|
| 화이트리스트 | 공개 경로만 명시, 나머지 전부 인증 | ✅ 채택 |
| `/auth` prefix | 경로에 `/auth/` 포함 여부로 구분 | ❌ |
| Route별 필터 | Gateway yml에서 route 단위로 필터 적용 | ❌ |

**화이트리스트 채택 근거**

- Spring Security의 `permitAll()` 방식과 동일한 원리로 업계 표준에 부합한다.
- 공개 API 목록이 한 곳에 명시되어 보안 검토가 용이하다.
- Routinely의 공개 API는 로그인, 회원가입, 토큰 갱신 3개뿐이므로 관리 부담이 없다.
- URL 구조가 리소스 중심으로 유지된다 (`/auth` prefix는 경로에 인증 의미가 노출됨).

**비채택 이유**

- `/auth` prefix: 기능적으로는 동작하지만, `/auth`가 "인증 관련 엔드포인트"와 "인증이 필요한 엔드포인트" 두 가지 의미로 혼동될 수 있다.
- Route별 필터: yml 설정이 커지면 공개/인증 경로가 분산되어 파악이 어렵다.

### Gateway Authentication Filter (JWT 직접 검증 + claims 전달)

```java
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    // 인증이 필요 없는 공개 경로 (화이트리스트)
    private static final List<String> PUBLIC_PATHS = List.of(
        "/api/v1/auth/login",
        "/api/v1/auth/register",
        "/api/v1/auth/refresh"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // 공개 경로면 JWT 검증 없이 통과
        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        // 인증 필요 경로: 토큰 추출 및 검증
        String token = extractToken(exchange);
        if (token == null) {
            return unauthorized(exchange); // 401
        }

        // Gateway에서 직접 검증 (네트워크 호출 없음)
        Claims claims = Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .getPayload();

        // claims 기반으로 사용자 컨텍스트 전달
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
            .header("X-User-Id", claims.get("id").toString())
            .header("X-Gateway-Secret", gatewaySecret)  // 내부 서비스 직접 접근 방지 (ADR-0019)
            .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }
}
```

### User Service (JWT 발급 시 필요한 정보를 claims에 포함)

```java
Jwts.builder()
    .subject(user.getEmail())
    .claim("id", user.getId())
    .claim("role", user.getRole())
    .signWith(key)
    .compact();
```

## Options Considered

### Option A: Gateway 직접 검증 (Selected)

- 성능: 빠름 (~1ms)
- 장애 격리: User Service 장애가 인증 전체 장애로 전파되지 않음
- JWT 철학: stateless에 부합
- 단점: 실시간 차단/최신 사용자 정보 반영은 추가 전략 필요(예: Redis 블랙리스트, MVP 이후)

### Option B: User Service 위임 검증 (Not selected)

- 장점:
    - 매 요청 시 DB 조회를 통해 최신 사용자 상태/차단 여부 반영 가능
    - "검증 + 사용자 조회"가 항상 필요한 요구가 있을 때 합리적
- 단점:
    - 네트워크 홉 증가, 지연 증가
    - User Service 장애가 전체 장애로 전파(SPOF)

## Summary Table

| 항목 | Gateway 직접 검증 | User Service 위임 |
|------|------------------|-------------------|
| 성능 | 빠름 (~1ms) | 느림 (네트워크+DB) |
| 장애 격리 | User Service 죽어도 인증 가능 | User Service 장애 = 전체 장애 |
| JWT 철학 | 부합 (stateless) | 벗어남 (사실상 stateful) |
| 실시간 사용자 차단 | 어려움 (만료까지 유효) | 가능 (DB로 확인) |
| 최신 사용자 정보 | 발급 시점 정보 | 매번 최신 |

## Notes

본 ADR은 "클라이언트 요청 인증 경로"에 대한 결정이다.  
서비스 간 비동기 이벤트(Kafka), 내부 잡 큐(PGMQ), 캐시/프레즌스/언리드/랭킹(Redis) 등은 별도 ADR로 관리한다.