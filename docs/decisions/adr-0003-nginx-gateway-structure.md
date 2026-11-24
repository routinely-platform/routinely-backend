# ADR-0003: Nginx + API Gateway 구조 채택

## Status
Accepted

---

## Context

Routinely는 다음과 같은 구조를 가진다:

- React 기반 프론트엔드
- Spring Boot 기반 마이크로서비스
- Spring Cloud Gateway 사용
- Eureka 기반 서비스 디스커버리
- EC2 단일 노드 + docker-compose 배포

외부 요청을 처리하는 Edge 레이어 구성에 대해 다음 선택지를 고려했다:

1. API Gateway만 사용
2. Nginx만 사용
3. Nginx + API Gateway 조합

---

## Decision

Routinely는 **Nginx + API Gateway 조합 구조**를 채택한다.

구조는 다음과 같다:

Client → Nginx → API Gateway → 각 마이크로서비스

---

## Roles and Responsibilities

### 1️⃣ Nginx (Edge Reverse Proxy)

**역할**

- TLS/HTTPS 처리
- 정적 리소스 서빙 (선택)
- 리버스 프록시
- 기본적인 Rate Limiting
- 요청 헤더 처리
- 압축(Gzip) 및 캐시 정책 적용

Nginx는 외부 인터넷과 직접 통신하는 가장 앞단에 위치한다.

---

### 2️⃣ API Gateway (Spring Cloud Gateway)

**역할**

- 서비스 라우팅
- 인증/인가 필터 적용
- 공통 필터 처리 (로깅, MDC, 트레이싱)
- 서비스 디스커버리 연동 (Eureka)
- 내부 서비스 간 경계 보호

Gateway는 내부 마이크로서비스의 진입점이다.

---

## Rationale

### 1️⃣ 책임 분리

- Nginx는 네트워크/인프라 레벨 책임
- Gateway는 애플리케이션 레벨 책임

역할을 분리하여 구조를 명확하게 유지한다.

---

### 2️⃣ 보안 강화

- TLS는 Nginx에서 처리
- 내부 서비스는 HTTP 통신
- 외부 노출은 Gateway 단일 엔드포인트

직접 서비스에 접근하는 경로를 차단한다.

---

### 3️⃣ 확장성 고려

향후 다음과 같은 확장이 가능하다:

- Nginx 레벨에서 Rate Limit 강화
- WAF 연동
- CDN 연동
- Blue/Green 배포 구조 도입

---

### 4️⃣ 운영 유연성

Nginx는 애플리케이션 재배포 없이도
설정 변경이 가능하다.

Gateway는 애플리케이션 레벨 라우팅을 담당한다.

---

## Alternative Considered

### Option 1: API Gateway만 사용

**장점**
- 구성 단순
- 인프라 구성 요소 감소

**단점**
- TLS, 정적 리소스, Rate Limit 등을 애플리케이션 레벨에서 처리해야 함
- 인프라 책임과 애플리케이션 책임 혼재

---

### Option 2: Nginx만 사용

**장점**
- 단순한 리버스 프록시 구성

**단점**
- 서비스 디스커버리 연동 어려움
- 인증/인가 로직 구현 복잡
- MSA 확장성 저하

---

## Consequences

### Positive

- 책임 분리 명확
- 보안 계층 분리
- 확장성 확보
- 운영 유연성 증가

### Negative

- 인프라 구성 요소 증가
- 설정 관리 복잡도 증가
- 디버깅 경로가 길어짐

---

## Deployment Model

현재 배포 구조:

- EC2 단일 인스턴스
- docker-compose 기반
- Nginx 컨테이너
- Gateway 컨테이너
- 각 마이크로서비스 컨테이너

Nginx는 외부 포트(80/443)를 오픈하고,
Gateway는 내부 네트워크에서만 접근 가능하도록 구성한다.

---

## 클라이언트 IP 신뢰 경계 (#156)

Gateway가 보는 TCP 원격 주소는 항상 Nginx다. 그래서 Gateway의 IP 기반 Rate Limit(`RateLimitFilter`, 공개 경로의
`rate_limit:ip:{IP}` 키)은 클라이언트 IP를 `X-Forwarded-For`에서 읽는다.

`X-Forwarded-For`는 프록시마다 오른쪽에 값을 덧붙이는 헤더다. **왼쪽 값은 클라이언트가 임의로 쓸 수 있고,
믿을 수 있는 값은 신뢰 프록시가 기록한 오른쪽 끝뿐이다.**

```
클라이언트(1.2.3.4)가 "X-Forwarded-For: 9.9.9.9"를 보냄
→ Nginx가 덧붙인 뒤: "9.9.9.9, 1.2.3.4"
                      ↑ 조작 가능   ↑ Nginx가 기록 — 이 값을 쓴다
```

### 설정 — `rate-limit.trusted-proxy-count`

앞단 신뢰 프록시 대수다. Gateway는 `XForwardedRemoteAddressResolver.maxTrustedIndex(n)`으로
**오른쪽에서 n번째 값**을 쓴다. 헤더가 없거나 2개 이상이면 전부 버리고 원격 주소를 쓴다.

| 값 | 의미 | 쓰는 곳 |
|---|---|---|
| `0` | `X-Forwarded-For`를 읽지 않고 원격 주소를 쓴다 | `application.yaml` 기본값 — Nginx가 없는 local · 테스트 |
| `1` | 오른쪽 끝(Nginx가 기록한 값) | prod — Nginx 1대 |
| `n` | 오른쪽에서 n번째 | 앞단 프록시가 n대일 때(예: CDN + Nginx = 2). **Nginx를 포함한 모든 프록시가 X-Forwarded-For에 append할 때만** 성립한다 — Nginx가 덮어쓰면 앞단 기록이 사라져 count를 몇으로 두든 클라이언트 IP를 얻을 수 없다 |

- prod(`application-prod.yaml`)는 기본값이 없다. **누락하거나 비우면 기동에 실패**한다. 음수도 기동 실패.
- 값이 실제 프록시 대수와 다르면 조용히 틀린다. 오류가 나지 않으므로 프록시 구성을 바꿀 때 이 값도 함께 바꾼다.
  - 실제보다 **작으면**: 프록시의 IP가 키가 되어 모든 사용자가 한 버킷을 나눠 쓴다(가용성 장애)
  - 실제보다 **크면**: X-Forwarded-For가 append 체인이면 클라이언트가 왼쪽에 붙인 값이 키가 되어 Rate Limit이 우회된다.
    값이 n개보다 적으면 SCG는 왼쪽 끝을 고른다(`Math.max(0, 값 개수 - n)`)

### 전제 — 배포할 때 반드시 지킨다

resolver는 원격 주소가 누구인지 검사하지 않는다. 아래 전제가 지켜져야만 오른쪽 끝이 Nginx가 기록한 값이다.
배포용 compose와 Nginx 설정은 아직 저장소에 없으므로, 도입할 때 이 목록을 체크리스트로 쓴다.

1. **Gateway 포트를 외부에 publish하지 않는다.** Nginx만 80/443을 연다. Gateway는 내부 네트워크에서만 접근 가능하게 둔다
   (`ports:` 대신 `expose:` 또는 내부 네트워크만 연결). 헬스체크용으로라도 Gateway 포트를 열지 않는다.
2. **Nginx가 `X-Forwarded-For` 오른쪽 끝에 실제 접속 주소를 남긴다.** Nginx 1대일 때는 둘 중 하나를 쓴다. 어느 쪽이든
   오른쪽 끝은 `$remote_addr`이므로 `trusted-proxy-count = 1`이 맞다.
   ```nginx
   proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;  # 기존 값 뒤에 덧붙인다 (프록시 2대 이상이면 이것만)
   # 또는
   proxy_set_header X-Forwarded-For $remote_addr;                # 덮어쓴다 (Nginx 1대일 때만)
   ```
3. **앞단 프록시 구성이 바뀌면 `RATE_LIMIT_TRUSTED_PROXY_COUNT`도 같이 바꾼다.** 2대 이상이 되면 Nginx를 append
   (`$proxy_add_x_forwarded_for`)로 바꾼다. 덮어쓰기를 그대로 두면 count를 올려도 앞단 프록시 IP가 키가 된다.

### 전제가 깨지면

클라이언트가 Gateway에 직접 붙으면 오른쪽 끝 값도 클라이언트가 정한다.

- 요청마다 값을 바꿔 IP 기반 Rate Limit을 우회할 수 있다.
- 값이 IP 리터럴이 아니면(예: 호스트명) resolver가 `new InetSocketAddress(값, 0)`에서 **DNS 조회**를 시도한다.
  WebFlux 이벤트 루프 스레드를 블로킹하므로, 느린 DNS를 노린 요청이 다른 요청까지 멈출 수 있다.

전제를 지키는 한 오른쪽 끝은 항상 Nginx가 기록한 IP이므로 두 문제 모두 생기지 않는다. 그래서 IP 형식 검증을
직접 구현하지 않고 네트워크 경계에 맡긴다. Gateway로 직접 들어오는 경로가 필요해지면 이 결정을 다시 본다.

---

## Architectural Principle

Edge Layer는 두 단계로 구성한다:

1. Infrastructure Edge (Nginx)
2. Application Edge (API Gateway)

Routinely는 이 이중 구조를 통해
보안, 확장성, 책임 분리를 동시에 달성한다.