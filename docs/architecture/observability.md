# Observability 전략

> **선택적 애드온 프로파일**: `observability`는 기본 활성화 대상이 아니다.  
> `observability` 프로파일은 단독으로 사용하지 않으며, 반드시 `local` 또는 `prod`와 조합한다.  
> - 로컬 개발 중 스택 확인: `local,observability` + `./scripts/local-up.sh --obs`  
> - 배포 환경에서 스택 활성화: `prod,observability`

## 스택 구성

| 역할 | 도구 |
|------|------|
| 분산 추적 계측 | `Micrometer Tracing` (micrometer-tracing-bridge-brave) |
| 분산 추적 백엔드 | `Zipkin` :9411 |
| 로그 수집 에이전트 | `Grafana Alloy` (Promtail 대체) |
| 로그 저장/쿼리 | `Loki` :3100 |
| 메트릭 수집 | `Prometheus` :9090 |
| 시각화 통합 | `Grafana` :3000 |
| 알림 | `Prometheus AlertManager` → Slack |

> 선택 근거: [ADR-0022](../decisions/adr-0022-observability-stack.md)

---

## 데이터 흐름

```
각 서비스 (Spring Boot 4.x)
  ├── /actuator/prometheus  ←── Prometheus (15s pull)
  ├── Micrometer Tracing    ──→ Zipkin :9411 (스팬 push)
  └── stdout JSON 로그      ──→ Grafana Alloy ──→ Loki :3100

Grafana :3000
  ├── Prometheus 데이터소스  (메트릭)
  └── Loki 데이터소스        (로그)

Zipkin UI :9411  (분산 추적, 별도 접근)
```

---

## 1. 분산 추적 (Micrometer Tracing + Zipkin)

Spring Boot 4.x에서 Spring Cloud Sleuth는 지원 종료되었다. **Micrometer Tracing**으로 대체한다.

### 의존성 (모든 서비스 공통)

```gradle
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'io.micrometer:micrometer-registry-prometheus'
implementation 'io.micrometer:micrometer-tracing-bridge-brave'
implementation 'io.zipkin.reporter2:zipkin-reporter-brave'
```

### 동작 방식

Spring Boot auto-configuration에 의해 별도 코드 없이 자동 동작한다:
- 인바운드 요청마다 `traceId` / `spanId` 자동 생성
- 모든 로그에 `traceId`, `spanId` 자동 주입 (Logback MDC)
- HTTP / gRPC 서비스 간 호출 시 추적 컨텍스트 자동 전파

### `application.yml`

```yaml
management:
  tracing:
    sampling:
      probability: 1.0   # 개발: 전수 / 프로덕션: 0.1 권장

zipkin:
  base-url: http://zipkin:9411
```

---

## 2. 로그 수집 (JSON + Grafana Alloy + Loki)

### 로그 출력 형식 — JSON 통일

모든 서비스는 `logback-spring.xml`로 **JSON 구조 로그를 stdout 출력**한다.

```json
{
  "timestamp": "2026-03-14T10:00:00.000Z",
  "level": "ERROR",
  "service": "routine-service",
  "traceId": "abc123",
  "spanId": "def456",
  "userId": 42,
  "message": "Outbox worker failed to publish event"
}
```

`traceId`를 통해 Grafana 로그 조회 중 Zipkin으로 드릴다운 가능.

### `logback-spring.xml`

```xml
<configuration>
  <springProperty scope="context" name="SERVICE_NAME" source="spring.application.name"/>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <customFields>{"service":"${SERVICE_NAME}"}</customFields>
    </encoder>
  </appender>
  <root level="INFO">
    <appender-ref ref="STDOUT"/>
  </root>
</configuration>
```

```gradle
implementation 'net.logstash.logback:logstash-logback-encoder:7.4'
```

### MDC userId 주입

```java
MDC.put("userId", request.getHeader("X-User-Id"));
try {
    filterChain.doFilter(request, response);
} finally {
    MDC.clear();
}
```

---

## 3. 메트릭 수집 (Prometheus)

### Actuator 설정

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, metrics
```

> Gateway 라우팅에서 `/actuator/**` 경로는 제외한다.

### 서비스별 주요 메트릭

| 서비스 | 주요 메트릭 |
|--------|-------------|
| API Gateway | 요청 수, 응답시간(p99), 4xx/5xx 비율, Rate Limit 차단 수 |
| user-service | 로그인 성공/실패 수, 토큰 발급 수 |
| challenge-service | gRPC 응답시간, 챌린지 생성 수 |
| routine-service | 루틴 완료 수, Outbox Worker 처리 건수 |
| chat-service | WebSocket 활성 연결 수, 메시지 처리량 |
| notification-service | SSE 활성 연결 수, PGMQ 처리 건수 |
| 공통 | JVM 힙 사용률, DB 커넥션 풀, Kafka consumer lag |

---

## 4. 알림 기준 (AlertManager)

| 조건 | 임계값 | 심각도 |
|------|--------|--------|
| 서비스 다운 | 응답 없음 1분 이상 | Critical |
| 높은 오류율 | 5xx 비율 5% 초과 (5분 윈도우) | Warning |
| Kafka consumer lag | 토픽당 1,000건 초과 | Warning |
| JVM 힙 사용률 | 85% 초과 (3분 지속) | Warning |
| Outbox 미발행 이벤트 | PENDING 상태 10분 이상 체류 | Warning |

Outbox 체류 시간은 routine-service에서 커스텀 `MeterRegistry` 게이지로 등록한다.

---

## 5. Docker Compose 컨테이너

| 컨테이너 | 포트 | 접근 범위 |
|----------|------|-----------|
| `zipkin` | 9411 | 개발 환경만 노출 |
| `prometheus` | 9090 | 개발 환경만 노출 |
| `loki` | 3100 | 내부 전용 |
| `alloy` | — | 내부 전용 |
| `grafana` | 3000 | 개발 환경 노출 / 프로덕션: Nginx 경유 또는 VPN |

> Observability 컨테이너는 `docker-compose.observability.yml`로 분리하여 필요 시에만 실행한다.

### 사용 방법

```bash
# 로컬 개발 중 Observability 스택 확인
./scripts/local-up.sh --obs
./gradlew :services:user-service:bootRun --args='--spring.profiles.active=local,observability'
```

인프라(`--obs`)와 서비스 프로파일(`local,observability`)은 세트로 사용한다. 인프라만 기동하고 프로파일을 `local`로 두면 Zipkin으로 스팬이 전송되지 않는다.

배포 환경에서 Observability 스택을 활성화할 때는 `prod,observability` 프로파일을 사용한다.
