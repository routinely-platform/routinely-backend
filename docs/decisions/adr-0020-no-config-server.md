# ADR-0020: Spring Cloud Config Server 미도입

## Status
Accepted

---

## Context

Routinely는 5개의 마이크로서비스(user, challenge, routine, chat, notification) + Gateway + registry-service(Eureka)로 구성된 MSA 구조이다.
Eureka 도입 결정은 ADR-0025 참조.

각 서비스는 다음과 같은 설정값을 필요로 한다:

- DB 접속 정보 (host, port, username, password)
- JWT 시크릿 키
- GATEWAY_SECRET
- Kafka 브로커 주소
- Redis 접속 정보
- 서비스 포트 및 Eureka 주소

MSA에서 설정을 중앙 집중 관리하는 수단으로 Spring Cloud Config Server를 검토하였다.

---

## Decision

**Spring Cloud Config Server를 도입하지 않는다.**

설정 관리는 다음 조합으로 처리한다:

- 비시크릿 설정: 서비스별 `application.yml` 정적 관리
- 시크릿: 환경변수 (`${ENV_VAR}`) 참조
- 환경별 주입: 로컬 `.env` / IntelliJ Run Configuration / AWS Secrets Manager

---

## Options Considered

### Option 1: Spring Cloud Config Server (기각)

Git 저장소에 설정 파일을 중앙 관리하고, 서비스 시작 시 Config Server에서 설정을 가져오는 방식.

```
서비스 기동
  └── Config Server 요청 (bootstrap phase)
        └── Git 저장소에서 application.yml 반환
              └── 서비스 설정 적용 후 정상 기동
```

**기각 이유:**

| 문제 | 설명 |
|------|------|
| SPOF 위험 | Config Server가 다운되면 모든 서비스 재기동 불가 — 이중화하면 복잡도 2배 증가 |
| 운영 복잡도 | 배포 대상 서비스가 하나 더 추가됨 (Config Server 자체의 관리 필요) |
| 컨테이너 환경과 부적합 | 환경변수 기반 설정이 컨테이너(Docker/ECS) 표준 — Config Server는 VM 시대 패턴 |
| 규모 미달 | 서비스 5개는 Config Server의 이점이 발휘되는 규모(10개 이상)에 미치지 못함 |
| 대안 존재 | AWS Secrets Manager가 시크릿 중앙 관리, 감사 로그, 환경별 분리를 이미 제공 |

---

### Option 2: 환경변수 + 서비스별 application.yml (채택)

**비시크릿 설정 — application.yml**

변경이 드문 설정(포트, Kafka 토픽명, 타임아웃 등)은 서비스별 `application.yml`에 정적으로 관리한다.

**시크릿 — 환경변수**

```yaml
# application.yml
spring:
  datasource:
    password: ${DB_PASSWORD}

gateway:
  secret: ${GATEWAY_SECRET}

jwt:
  secret: ${JWT_SECRET}
```

**환경별 주입 전략 (ADR-0019 참조)**

| 환경 | 주입 방법 |
|------|-----------|
| 로컬 IntelliJ | Run Configuration → Environment Variables |
| 로컬 Docker Compose | 프로젝트 루트 `.env` 파일 |
| AWS 배포 | Secrets Manager / SSM Parameter Store → ECS Task Definition secrets |

---

## Rationale

### 1. 컨테이너 환경에서 환경변수가 표준이다

12-Factor App 원칙에 따르면 설정은 환경변수로 분리하는 것이 표준이다.
Docker, ECS, Kubernetes 모두 환경변수 주입을 1급 기능으로 지원한다.
Config Server는 이 표준 위에 불필요한 계층을 추가한다.

### 2. AWS Secrets Manager가 Config Server의 핵심 기능을 이미 대체한다

| 기능 | Config Server | AWS Secrets Manager |
|------|:---:|:---:|
| 시크릿 중앙 관리 | ✅ | ✅ |
| 환경별 분리 (dev/prod) | ✅ | ✅ |
| 감사 로그 | ✅ | ✅ |
| 자동 로테이션 | ❌ | ✅ |
| 추가 서비스 운영 부담 | ❌ (있음) | ✅ (없음) |

### 3. 5개 서비스는 관리 가능한 규모다

서비스가 10개 미만이면 설정 파일을 서비스별로 관리해도 부담이 크지 않다.
공통 설정이 많지 않아 중앙화의 이점도 제한적이다.

### 4. Config Server는 SPOF다

Config Server가 다운되면 모든 서비스의 재기동이 불가능해진다.
이를 방지하려면 Config Server 자체를 이중화해야 하며, 복잡도가 선형으로 증가한다.

---

## Consequences

### Positive

- 서비스 구성 단순화 (배포 대상 감소)
- 기동 의존성 제거 (Config Server 없어도 모든 서비스 독립 기동 가능)
- 컨테이너 환경 표준 방식 준수
- AWS 관리형 서비스로 시크릿 보안 수준 유지

### Negative

- 설정 변경 시 서비스 재배포 필요 (동적 갱신 불가)
  - 완화: 설정 변경이 잦은 값은 DB 또는 Redis로 관리
- 서비스별 application.yml이 분산되어 전체 설정 파악에 더 많은 노력 필요
  - 완화: 서비스 수가 5개로 한정되어 있어 관리 가능한 수준

---

## 재검토 기준

다음 조건이 발생하면 Config Server 도입을 재검토한다:

- 서비스 수가 10개 이상으로 증가하여 설정 파일 관리가 어려워질 때
- 재배포 없이 설정을 동적으로 변경해야 하는 요구사항이 생길 때
- 여러 서비스가 공유하는 설정값이 많아져 중복 관리 비용이 커질 때

---

## Architectural Principle

> 기술 도입은 현재 규모의 문제를 해결할 때 정당화된다.
>
> Config Server는 Routinely MVP 규모에서 해결해야 할 문제가 없다.
> 환경변수와 AWS Secrets Manager의 조합으로 충분하다.
