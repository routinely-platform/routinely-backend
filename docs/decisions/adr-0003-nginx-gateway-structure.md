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

## Architectural Principle

Edge Layer는 두 단계로 구성한다:

1. Infrastructure Edge (Nginx)
2. Application Edge (API Gateway)

Routinely는 이 이중 구조를 통해
보안, 확장성, 책임 분리를 동시에 달성한다.