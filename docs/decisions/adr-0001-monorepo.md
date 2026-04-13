# ADR-0001: Monorepo 구조 채택

## Status
Accepted

## Context
Routinely는 개인 프로젝트이며, 다수의 마이크로서비스를 포함한다.

- Gateway
- User
- Challenge
- Routine
- Notification
- Chat

멀티 리포지토리 구조와 모노레포 구조 중 선택이 필요했다.

## Decision
모노레포(Monorepo) 구조를 채택한다.

## Rationale

- 개인 프로젝트이므로 CI/CD 복잡도 최소화
- 서비스 간 공통 코드 공유가 용이
- Gradle 멀티모듈로 버전 일관성 유지 가능
- 단일 브랜치 전략으로 관리 단순화

## Consequences

Positive:
- 관리 편의성 증가
- 버전 충돌 감소
- 공통 설정 중앙 관리 가능

Negative:
- 리포지토리 규모 증가
- 서비스 단위 독립 배포 전략이 다소 복잡해질 수 있음