# ADR-0012: Outbox 패턴 도입 여부

## Status
Accepted

---

## Context

Routinely는 Kafka를 사용하여 서비스 간 도메인 이벤트를 전달한다.

예:

- routine.execution.done
- challenge.member.joined
- challenge.member.left
- chat.message.created

이벤트 발행 과정에서 다음과 같은 문제가 발생할 수 있다:

1. DB 트랜잭션은 성공했지만 Kafka publish는 실패
2. DB에는 데이터가 저장되었지만 이벤트는 전달되지 않음
3. 후속 서비스(Notification 등)가 동작하지 않음
4. 시스템 불일치 발생 (Inconsistent State)

Routinely는 알림, 통계, 랭킹이 핵심 기능이므로
이벤트 유실은 허용하기 어렵다.

따라서 DB 커밋과 이벤트 발행 간의 정합성을 보장할 전략이 필요했다.

---

## Decision

Routinely는 **Outbox 패턴을 도입한다.**

단, Debezium 기반 CDC 방식은 사용하지 않고  
애플리케이션 레벨 Outbox (Outbox-lite) 방식을 채택한다.

---

## Selected Approach: Application-Level Outbox

### 기본 개념

1. 비즈니스 트랜잭션 내에서
    - 도메인 데이터 저장
    - outbox 테이블에 이벤트 레코드 저장

2. 별도 워커가 outbox 테이블을 polling
3. Kafka에 이벤트 publish
4. 성공 시 상태를 SENT로 변경
5. 실패 시 재시도 (백오프 정책 적용)

---

## Why Not Direct Kafka Publish?

다음 방식은 안전하지 않다:

```java
@Transactional
public void complete() {
    executionRepository.save(...);
    kafkaTemplate.send("routine.execution.done", event);
}