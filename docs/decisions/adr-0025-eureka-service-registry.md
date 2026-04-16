# ADR-0025: Eureka 서비스 레지스트리 도입 — registry-service

## Status
Accepted

---

## Context

Routinely는 5개의 마이크로서비스 + Gateway로 구성된 MSA 구조다.

Gateway가 내부 서비스로 요청을 라우팅할 때, 각 서비스의 주소를 어떻게 해소(resolve)할지 결정해야 한다.

검토한 옵션:

1. **Docker Compose DNS 정적 참조** — `http://user-service:8081` 형태로 서비스명 직접 사용
2. **Eureka 서비스 레지스트리** — 서비스가 동적으로 등록하고, Gateway가 `lb://user-service`로 조회

---

## Decision

**Eureka 서버를 `registry-service`로 도입한다.**

- 서비스명: `registry-service`
- HTTP 포트: `8761` (Eureka 기본값)
- 위치: `services/registry-service/`
- 각 서비스(Gateway 포함)는 Eureka Client로 등록한다

---

## Options Considered

### Option 1: Docker Compose DNS 정적 참조 (기각)

```yaml
# gateway application.yml
spring:
  cloud:
    gateway:
      routes:
        - uri: http://user-service:8081
```

**기각 이유:**

| 문제 | 설명 |
|------|------|
| 정적 주소 고정 | 포트 변경 시 설정 파일 전체 수정 필요 |
| 로드밸런싱 불가 | 동일 서비스의 다중 인스턴스 라우팅 불가 |
| 헬스체크 미반영 | 다운된 인스턴스로 라우팅 가능 |
| 일관성 부재 | 로컬/Docker/k8s 환경마다 주소 관리 방식이 달라짐 |

---

### Option 2: Eureka 서비스 레지스트리 (채택)

```yaml
# gateway application.yml
spring:
  cloud:
    gateway:
      routes:
        - uri: lb://user-service   # Eureka 기반 로드밸런싱
```

**채택 이유:**

| 장점 | 설명 |
|------|------|
| 동적 서비스 등록 | 서비스 기동 시 자동 등록, 종료 시 자동 해제 |
| 로드밸런싱 | 다중 인스턴스 배포 시 Round-Robin 자동 적용 |
| 헬스체크 연동 | 비정상 인스턴스 자동 제거 |
| 일관된 라우팅 | 환경(로컬/Docker/EC2) 무관하게 서비스명으로 통일 |
| 경험 활용 | miri-miri 프로젝트에서 검증된 패턴 |

---

## Implementation

> **참고**: 이 ADR 작성 시점에 Eureka 설정값 자체의 변경 여부는 아직 확정되지 않았다.  
> registry-service의 Eureka 설정을 검토하는 과정에서 먼저 "이 프로젝트에서 프로파일을 어떻게 사용할지"를 명확히 할 필요가 있었다.  
> 현재 확정된 프로파일 정책은 다음과 같다: `local` (평소 개발) / `local,observability` (로컬 + Observability 스택) / `prod` (배포) / `prod,observability` (배포 + Observability 스택). `observability`는 독립 애드온 프로파일로, 단독 사용 없이 `local` 또는 `prod`와 조합한다.  
> Eureka 설정 변경이 필요하다고 판단될 경우, 별도 ADR 또는 이 ADR의 개정으로 기록한다.

### registry-service 구성

```
services/registry-service/
├── src/main/java/com/routinely/registry/
│   └── RegistryServiceApplication.java
└── src/main/resources/
    └── application.yml
```

```java
// RegistryServiceApplication.java
@SpringBootApplication
@EnableEurekaServer
public class RegistryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(RegistryServiceApplication.class, args);
    }
}
```

```yaml
# application.yml
server:
  port: 8761

spring:
  application:
    name: registry-service

eureka:
  client:
    register-with-eureka: false   # 자기 자신을 등록하지 않음
    fetch-registry: false
  server:
    wait-time-in-ms-when-sync-empty: 0  # 시작 시 대기 시간 제거
```

---

### 각 서비스 — Eureka Client 설정

```yaml
# 각 서비스 application.yml 공통
eureka:
  client:
    service-url:
      defaultZone: http://registry-service:8761/eureka/
  instance:
    prefer-ip-address: true
```

```yaml
# application-local.yml (로컬 IntelliJ 직접 실행 시)
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
```

---

### Gateway — lb:// 라우팅

```yaml
# gateway application.yml
spring:
  cloud:
    gateway:
      routes:
        - id: user-service
          uri: lb://user-service
          predicates:
            - Path=/api/v1/auth/**, /api/v1/users/**

        - id: challenge-service
          uri: lb://challenge-service
          predicates:
            - Path=/api/v1/challenges/**
```

---

### 기동 순서

```
1. registry-service  (Eureka Server)
2. gateway           (Eureka Client + 라우팅)
3. user-service, routine-service, challenge-service, chat-service, notification-service
```

Docker Compose `depends_on`으로 순서 보장:

```yaml
services:
  gateway:
    depends_on:
      - registry-service

  user-service:
    depends_on:
      - registry-service
```

---

### libs 의존성

`registry-service`는 공유 라이브러리(`common-core`, `common-web`, `common-observability`, `proto`)를 의존하지 않는다.
순수 Eureka Server로서 Spring Boot Starter와 `spring-cloud-starter-netflix-eureka-server`만 사용한다.

---

## Config Server 미도입과의 관계

ADR-0020에서 Config Server를 도입하지 않기로 결정했다.
Eureka는 Config Server와 독립적으로 운영 가능하며, 설정은 각 서비스의 `application.yml` + 환경변수로 관리한다.

---

## Consequences

### Positive

- Gateway 라우팅이 동적으로 동작 — 서비스 추가 시 설정 변경 불필요
- 다중 인스턴스 배포 시 로드밸런싱 자동 적용
- Eureka 대시보드(`http://localhost:8761`)로 서비스 상태 한눈에 확인 가능
- 로컬/Docker/EC2 환경 모두 동일한 라우팅 방식 유지

### Negative

- 서비스 기동 시 `registry-service`가 먼저 기동되어야 함 (SPOF 가능성)
  - 완화: 각 서비스에 `eureka.client.enabled: false` 옵션으로 Eureka 없이도 기동 가능하게 설정 가능
- 배포 대상 서비스가 1개 증가
  - 완화: registry-service는 상태를 갖지 않아 운영 부담이 매우 낮음

---

## Architectural Principle

> 서비스 주소는 코드와 설정에 하드코딩하지 않는다.
> 서비스 레지스트리를 통해 동적으로 해소한다.
