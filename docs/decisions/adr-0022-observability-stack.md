# ADR-0022: Observability 스택 선택 — Zipkin + Loki + Prometheus + Grafana

## Status
Accepted

---

## Context

Routinely는 5개의 마이크로서비스(user, challenge, routine, chat, notification)와 API Gateway로 구성된 MSA 환경이다.

분산 환경에서 다음 세 가지 관측 요구사항을 해결해야 했다:

1. **분산 추적** — 여러 서비스에 걸친 단일 요청의 흐름을 추적하고 병목을 파악한다.
2. **로그 집계** — 서비스별로 분산된 로그를 중앙에서 수집·조회한다.
3. **메트릭 수집** — JVM 상태, 요청 처리량, 오류율 등을 수집하고 알림을 트리거한다.

추가 기술적 제약:

- **Spring Boot 4.x**에서는 Spring Cloud Sleuth가 지원 종료되었다. 분산 추적은 **Micrometer Tracing**으로 대체된다.
- MVP 단계이므로 인프라 운영 복잡도와 리소스 비용을 최소화해야 한다.

---

## Decision

다음 스택을 채택한다:

| 역할 | 선택 기술 |
|------|-----------|
| 분산 추적 계측 | `Micrometer Tracing` (micrometer-tracing-bridge-brave) |
| 분산 추적 백엔드 | `Zipkin` |
| 로그 수집 에이전트 | `Grafana Alloy` |
| 로그 저장/쿼리 | `Loki` |
| 메트릭 수집 | `Prometheus` |
| 시각화 통합 | `Grafana` |

---

## Alternatives Considered

### 분산 추적: Spring Cloud Sleuth (기각)

Spring Boot 3 이상에서 공식 지원이 종료되었다.
Spring Boot 4.x 환경에서는 Micrometer Tracing이 표준이다.

### 로그: ELK 스택 (기각)

| 문제 | 설명 |
|------|------|
| 높은 리소스 사용 | Elasticsearch는 JVM 기반으로 메모리 요구량이 높음 |
| 운영 복잡도 | Elasticsearch + Logstash + Kibana 3개 컴포넌트 운영 필요 |
| MVP 오버킬 | 5개 서비스 규모에서 Elasticsearch 클러스터 유지는 비용 대비 이득 없음 |

Loki는 인덱스 없이 레이블 기반으로 로그를 저장하므로 스토리지 사용량이 적고,
Prometheus와 동일한 쿼리 모델을 공유하여 Grafana에서 메트릭·로그를 통합 조회할 수 있다.

### 로그 수집 에이전트: Promtail (대체)

Promtail은 Loki 전용 에이전트로 기능이 제한적이다.
**Grafana Alloy**는 Promtail을 대체하는 차세대 범용 수집 에이전트로,
OpenTelemetry Collector 역할까지 통합하여 향후 OTel 기반 확장이 용이하다.

### 시각화: Grafana Tempo (제외)

Zipkin 추적 데이터를 Grafana 내에서 직접 조회하려면 Grafana Tempo가 필요하다.
MVP 단계에서는 Zipkin 자체 UI로 분산 추적을 조회하고,
Grafana에서는 로그의 `traceId` 필드를 통해 Zipkin으로 딥링크 이동하는 방식으로 대체한다.

---

## Rationale

### 1. Micrometer Tracing

Spring Boot auto-configuration으로 TraceId/SpanId가 자동 생성·주입·전파된다.
별도 코드 없이 HTTP, gRPC, Kafka 컨텍스트 전파가 동작한다.

### 2. Zipkin

- 오픈소스, 경량 분산 추적 백엔드
- Brave 리포터와 조합하여 Micrometer Tracing과 즉시 연동
- In-memory 또는 PostgreSQL 저장소 선택 가능

### 3. Loki + Grafana Alloy

- Loki: Elasticsearch 대비 리소스 사용 최소화, Grafana와 네이티브 통합
- Alloy: Promtail보다 확장성 높고 OTel 지원으로 미래 마이그레이션 용이

### 4. Prometheus + Grafana

- Pull 기반 스크랩으로 서비스 코드 수정 없이 메트릭 수집
- `/actuator/prometheus` 엔드포인트로 JVM, HTTP, 커스텀 메트릭 제공
- Grafana에서 Prometheus(메트릭)와 Loki(로그)를 단일 대시보드로 통합 조회

---

## Architecture Overview

```
각 서비스 (Spring Boot 4.x)
  ├── /actuator/prometheus  ←─── Prometheus (15s pull)
  ├── Micrometer Tracing    ───→ Zipkin :9411 (스팬 push)
  └── stdout JSON 로그      ───→ Grafana Alloy ───→ Loki :3100

Grafana :3000
  ├── Prometheus 데이터소스  (메트릭 조회)
  └── Loki 데이터소스        (로그 조회)

Zipkin UI :9411  (분산 추적, 별도 접근)
Prometheus AlertManager  (임계값 기반 알림 → Slack)
```

---

## Docker Compose 추가 컨테이너

| 컨테이너 | 포트 | 접근 범위 |
|----------|------|-----------|
| `zipkin` | 9411 | 개발 환경만 노출 |
| `prometheus` | 9090 | 개발 환경만 노출 |
| `loki` | 3100 | 내부 전용 |
| `alloy` | — | 내부 전용 |
| `grafana` | 3000 | 개발 환경 노출 / 프로덕션: Nginx 경유 또는 VPN |

---

## Consequences

### Positive

- Spring Boot 4.x 표준 계측 방식(Micrometer Tracing) 채택 → Sleuth 마이그레이션 불필요
- ELK 대비 인프라 리소스 절감 (Loki의 인덱스 없는 저장 방식)
- Grafana 단일 대시보드에서 메트릭과 로그 통합 조회
- Grafana Alloy 도입으로 향후 OpenTelemetry 전환 경로 확보
- Docker Compose 컨테이너 5개 추가만으로 완전한 Observability 구성

### Negative

- Docker Compose 컨테이너 수 증가로 로컬 개발 시 메모리 사용량 증가
  - 완화: Observability 컨테이너는 별도 `docker-compose.observability.yml`로 분리하여 선택적 실행
- Zipkin이 Grafana에 통합되지 않아 추적 조회 시 UI 전환 필요
  - 완화: 로그의 `traceId` 딥링크로 Zipkin 이동 가능 / 향후 Grafana Tempo로 마이그레이션

---

## Future Considerations

- 트래픽 증가 시 Zipkin 저장소를 in-memory → PostgreSQL / Elasticsearch로 전환
- Grafana Tempo 도입으로 Grafana 내 분산 추적 통합 조회
- OpenTelemetry Collector 도입 시 Grafana Alloy를 그대로 활용 가능

---

## 관련 문서

- [로그 수집 전략](../observability/logging.md)
- [메트릭 수집 및 분산 추적](../observability/monitoring.md)
- [알림 기준](../observability/alerting.md)
