# gRPC 구현 가이드

> Spring gRPC (공식 Spring 프로젝트, `org.springframework.grpc`) 기반.
> Spring Boot 4.x와 함께 공식 지원된다.

---

## 1. 의존성 구조

```
libs/proto              → .proto 정의 + 컴파일된 스텁 (모든 서비스가 참조)
services/challenge-service  → spring-grpc-server-spring-boot-starter (서버)
services/routine-service    → spring-grpc-server-spring-boot-starter (서버)
services/chat-service       → spring-grpc-client-spring-boot-starter (클라이언트)
services/routine-service    → spring-grpc-client-spring-boot-starter (클라이언트)
```

gRPC 호출 관계 (ADR-0007):
- `chat-service` → `challenge-service` : `CheckMembership`
- `routine-service` → `challenge-service` : `GetChallengeContext`

---

## 2. libs/proto 빌드 설정

```gradle
// libs/proto/build.gradle
plugins {
    id 'com.google.protobuf' version '0.9.4'
}

ext {
    grpcVersion     = '1.68.1'
    protobufVersion = '4.28.3'
}

dependencies {
    implementation 'javax.annotation:javax.annotation-api:1.3.2'
    implementation "io.grpc:grpc-protobuf:${grpcVersion}"
    implementation "io.grpc:grpc-stub:${grpcVersion}"
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${protobufVersion}"
    }
    plugins {
        grpc {
            artifact = "io.grpc:protoc-gen-grpc-java:${grpcVersion}"
        }
    }
    generateProtoTasks {
        all()*.plugins {
            grpc {}
        }
    }
}
```

> `java` 플러그인은 루트 `build.gradle`의 `subprojects` 블록에서 이미 적용됨.
> 생성된 Java 스텁 위치: `build/generated/source/proto/main/`
> IDE에서 인식하려면 해당 경로를 소스 루트로 추가해야 할 수 있음.

---

## 3. 서비스별 gRPC 의존성

Spring Initializr에서 "Spring gRPC Server" / "Spring gRPC Client" 선택 → 버전은 Spring Boot BOM 자동 관리.

```gradle
// gRPC 서버 역할인 서비스 (challenge-service, routine-service)
dependencies {
    implementation project(':libs:proto')
    implementation 'org.springframework.grpc:spring-grpc-server-spring-boot-starter'
}

// gRPC 클라이언트 역할인 서비스 (chat-service, routine-service)
dependencies {
    implementation project(':libs:proto')
    implementation 'org.springframework.grpc:spring-grpc-client-spring-boot-starter'
}
```

> routine-service는 서버 + 클라이언트 둘 다 추가한다.

---

## 4. .proto 파일 작성 규칙

```protobuf
// libs/proto/src/main/proto/challenge_service.proto
syntax = "proto3";

package routinely.challenge.v1;

option java_multiple_files = true;
option java_package = "com.routinely.proto.challenge";
option java_outer_classname = "ChallengeServiceProto";

service ChallengeGrpcService {
  rpc CheckMembership (CheckMembershipRequest) returns (CheckMembershipResponse);
  rpc GetChallengeContext (GetChallengeContextRequest) returns (GetChallengeContextResponse);
}

// ── CheckMembership ──────────────────────────────────────────────────

message CheckMembershipRequest {
  int64 challenge_id = 1;
  int64 user_id      = 2;
}

message CheckMembershipResponse {
  bool   is_active_member = 1;  // ACTIVE 상태 멤버 여부
  string member_role      = 2;  // "LEADER" | "MEMBER" — is_active_member=false 이면 빈 문자열
}

// ── GetChallengeContext ──────────────────────────────────────────────

message GetChallengeContextRequest {
  int64 routine_template_id = 1;  // 실행하려는 루틴 템플릿 ID
  int64 user_id             = 2;
}

message GetChallengeContextResponse {
  bool   is_challenge_routine = 1;  // 해당 루틴이 챌린지 소속 루틴인지
  int64  challenge_id         = 2;  // is_challenge_routine=false 이면 0
  string challenge_status     = 3;  // "WAITING" | "ACTIVE" | "ENDED"
  bool   is_member_active     = 4;  // 해당 사용자가 ACTIVE 멤버인지
}
```

### 날짜/시간 타입 규칙

proto3에는 DateTime 타입이 없으므로 `int64`로 전달하고 양 끝에서 변환한다.

| Java 타입 | proto 타입 | 변환 방식 |
|---|---|---|
| `LocalDate` | `int64` | `atStartOfDay(ZoneId.systemDefault()).toEpochSecond()` |
| `LocalDateTime` | `int64` | `atZone(ZoneId.systemDefault()).toEpochSecond()` |
| `Instant` | `int64` | `getEpochSecond()` |

> EpochSecond(초) 기준으로 통일. 밀리초가 필요한 경우에만 EpochMilli 사용.

### 필드 번호 규칙

- 한번 배포된 .proto의 필드 번호는 변경하지 않는다
- 필드 삭제 시 번호를 재사용하지 않고 `reserved` 처리
- 새 필드는 항상 새 번호 부여

---

## 5. gRPC 서버 구현 패턴

### Application 클래스

```java
@SpringBootApplication
@EnableDiscoveryClient   // Eureka 등록
public class ChallengeServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChallengeServiceApplication.class, args);
    }
}
```

### gRPC 서비스 구현

```java
// presentation/grpc/ChallengeGrpcServiceImpl.java
@GrpcService
@RequiredArgsConstructor
@Slf4j
public class ChallengeGrpcServiceImpl extends ChallengeGrpcServiceGrpc.ChallengeGrpcServiceImplBase {

    private final ChallengeQueryService challengeQueryService;

    @Override
    public void checkMembership(CheckMembershipRequest request,
                                StreamObserver<CheckMembershipResponse> responseObserver) {
        try {
            MembershipResult result = challengeQueryService.checkMembership(
                request.getChallengeId(), request.getUserId()
            );

            CheckMembershipResponse response = CheckMembershipResponse.newBuilder()
                    .setIsActiveMember(result.isActive())
                    .setMemberRole(result.isActive() ? result.getRole().name() : "")
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (BusinessException e) {
            Status status = e.getErrorCode().getHttpStatus() == HttpStatus.NOT_FOUND
                    ? Status.NOT_FOUND : Status.INTERNAL;
            responseObserver.onError(status.withDescription(e.getMessage()).asRuntimeException());
        } catch (Exception e) {
            log.error("checkMembership gRPC error - challengeId: {}, userId: {}",
                request.getChallengeId(), request.getUserId(), e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getChallengeContext(GetChallengeContextRequest request,
                                    StreamObserver<GetChallengeContextResponse> responseObserver) {
        try {
            ChallengeContextResult result = challengeQueryService.getChallengeContext(
                request.getRoutineTemplateId(), request.getUserId()
            );

            GetChallengeContextResponse response = GetChallengeContextResponse.newBuilder()
                    .setIsChallengeRoutine(result.isChallengeRoutine())
                    .setChallengeId(result.isChallengeRoutine() ? result.getChallengeId() : 0L)
                    .setChallengeStatus(result.isChallengeRoutine() ? result.getStatus().name() : "")
                    .setIsMemberActive(result.isMemberActive())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (BusinessException e) {
            Status status = e.getErrorCode().getHttpStatus() == HttpStatus.NOT_FOUND
                    ? Status.NOT_FOUND : Status.INTERNAL;
            responseObserver.onError(status.withDescription(e.getMessage()).asRuntimeException());
        } catch (Exception e) {
            log.error("getChallengeContext gRPC error - templateId: {}, userId: {}",
                request.getRoutineTemplateId(), request.getUserId(), e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }
}
```

### 서버 application.yml

```yaml
spring:
  application:
    name: challenge-service
  grpc:
    server:
      port: 9083   # HTTP 포트 + 1000 규칙

server:
  port: 8083

eureka:
  client:
    service-url:
      defaultZone: http://registry-service:8761/eureka/
  instance:
    prefer-ip-address: true
```

---

## 6. gRPC 클라이언트 구현 패턴

### 클라이언트 Stub 빈 등록

Spring gRPC는 `GrpcChannelFactory`를 통해 채널을 생성하고, 생성된 채널로 stub을 빈으로 등록한다.

```java
// infrastructure/grpc/ChallengeGrpcClient.java
@Component
@Slf4j
public class ChallengeGrpcClient {

    private final ChallengeServiceGrpc.ChallengeServiceBlockingStub stub;

    public ChallengeGrpcClient(GrpcChannelFactory channelFactory) {
        this.stub = ChallengeServiceGrpc.newBlockingStub(
            channelFactory.createChannel("challenge-service")
        );
    }

    public boolean checkMembership(Long challengeId, Long userId) {
        try {
            CheckMembershipRequest request = CheckMembershipRequest.newBuilder()
                    .setChallengeId(challengeId)
                    .setUserId(userId)
                    .build();
            CheckMembershipResponse response = stub.checkMembership(request);
            return response.getIsActiveMember();

        } catch (StatusRuntimeException e) {
            log.error("checkMembership gRPC failed - status: {}", e.getStatus(), e);
            throw new BusinessException(ErrorCode.GRPC_CALL_FAILED);
        }
    }

    public ChallengeContext getChallengeContext(Long routineTemplateId, Long userId) {
        try {
            GetChallengeContextRequest request = GetChallengeContextRequest.newBuilder()
                    .setRoutineTemplateId(routineTemplateId)
                    .setUserId(userId)
                    .build();
            GetChallengeContextResponse response = stub.getChallengeContext(request);
            return ChallengeContext.fromProto(response);

        } catch (StatusRuntimeException e) {
            log.error("getChallengeContext gRPC failed - templateId: {}, userId: {}",
                routineTemplateId, userId, e);
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
            }
            throw new BusinessException(ErrorCode.GRPC_CALL_FAILED);
        }
    }
}
```

### 클라이언트 application.yml

```yaml
# chat-service application.yml
spring:
  grpc:
    client:
      channels:
        challenge-service:
          address: "discovery:///challenge-service"   # Eureka 기반 서비스 해소
          negotiation-type: plaintext                 # 내부망 → TLS 없음 (ADR-0019)
```

> `discovery:///` 프리픽스를 사용하면 Eureka에서 `challenge-service`를 찾아 gRPC 포트로 연결.

---

## 7. Proto ↔ 도메인 변환 패턴

gRPC 응답을 도메인에서 받아쓰는 쪽(클라이언트 서비스)에 변환 객체를 둔다.

```java
// chat-service: infrastructure/grpc/dto/ChallengeContext.java
@Getter
@AllArgsConstructor
public class ChallengeContext {

    private final Long   challengeId;
    private final String status;
    private final boolean memberActive;

    public static ChallengeContext fromProto(GetChallengeContextResponse proto) {
        return new ChallengeContext(
            proto.getChallengeId(),
            proto.getChallengeStatus(),
            proto.getIsMemberActive()
        );
    }

    public boolean isActive() {
        return "ACTIVE".equals(this.status);
    }
}
```

---

## 8. gRPC 에러 처리 규칙

### 서버 측 에러 전달

```java
// 비즈니스 예외 → gRPC Status 코드로 변환해서 전달
} catch (BusinessException e) {
    Status status = switch (e.getErrorCode().getHttpStatus()) {
        case NOT_FOUND -> Status.NOT_FOUND;
        case FORBIDDEN -> Status.PERMISSION_DENIED;
        default        -> Status.INTERNAL;
    };
    responseObserver.onError(
        status.withDescription(e.getMessage()).asRuntimeException()
    );
}
```

### 클라이언트 측 에러 처리 (ADR-0007 원칙)

```java
} catch (StatusRuntimeException e) {
    Status.Code code = e.getStatus().getCode();
    if (code == Status.Code.NOT_FOUND)          throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);   // HTTP 404
    if (code == Status.Code.DEADLINE_EXCEEDED)  throw new BusinessException(ErrorCode.GRPC_TIMEOUT);          // HTTP 503
    throw new BusinessException(ErrorCode.GRPC_CALL_FAILED);                                                   // HTTP 500
}
```

> ADR-0007: gRPC 비즈니스 판단은 Status Code가 아닌 **응답 필드**로 전달.
> Status Code는 인프라 수준 실패(NOT_FOUND, INTERNAL, DEADLINE_EXCEEDED)에만 사용.

---

## 9. Resilience4j 연동 (ADR-0007)

gRPC 클라이언트 호출에 Resilience4j Circuit Breaker + Timeout 적용.

```java
@Component
@RequiredArgsConstructor
public class ChallengeGrpcClient {

    private final ChallengeServiceGrpc.ChallengeServiceBlockingStub stub;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public ChallengeGrpcClient(GrpcChannelFactory channelFactory,
                                CircuitBreakerRegistry circuitBreakerRegistry) {
        this.stub = ChallengeServiceGrpc.newBlockingStub(
            channelFactory.createChannel("challenge-service")
        );
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    public boolean checkMembership(Long challengeId, Long userId) {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("challengeServiceGrpc");

        return cb.executeSupplier(() -> {
            CheckMembershipRequest request = CheckMembershipRequest.newBuilder()
                    .setChallengeId(challengeId)
                    .setUserId(userId)
                    .build();
            // Timeout은 stub에 직접 설정
            return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                       .checkMembership(request)
                       .getIsActiveMember();
        });
    }
}
```

```yaml
# application.yml
resilience4j:
  circuitbreaker:
    instances:
      challengeServiceGrpc:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 5m
        sliding-window-size: 50
        minimum-number-of-calls: 20
```

---

## 10. 로컬 개발 환경 — Eureka 없이 직접 연결

로컬에서 registry-service 없이 특정 서비스만 단독 실행할 때.

```yaml
# application-local.yml (클라이언트 서비스)
spring:
  grpc:
    client:
      channels:
        challenge-service:
          address: "static://localhost:9083"   # 직접 주소 지정
          negotiation-type: plaintext
```

---

## 11. 패키지 위치 요약

```
libs/proto/
└── src/main/proto/
    ├── challenge_service.proto
    └── routine_service.proto          # (필요 시 추가)

services/challenge-service/
└── src/main/java/com/routinely/challenge/
    └── presentation/
        └── grpc/
            └── ChallengeGrpcServiceImpl.java   # @GrpcService 구현

services/chat-service/
└── src/main/java/com/routinely/chat/
    └── infrastructure/
        └── grpc/
            ├── ChallengeGrpcClient.java    # GrpcChannelFactory 사용
            └── dto/
                └── ChallengeContext.java   # fromProto() 변환 객체

services/routine-service/
└── src/main/java/com/routinely/routine/
    └── infrastructure/
        └── grpc/
            ├── ChallengeGrpcClient.java
            └── dto/
                └── ChallengeContext.java
```
