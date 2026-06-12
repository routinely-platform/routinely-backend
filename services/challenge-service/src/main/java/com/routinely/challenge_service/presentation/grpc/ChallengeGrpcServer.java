package com.routinely.challenge_service.presentation.grpc;

import com.routinely.challenge_service.application.ChallengeMembershipQueryService;
import com.routinely.challenge_service.application.dto.ChallengeContextResult;
import com.routinely.challenge_service.application.dto.MembershipCheckResult;
import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorStatus;
import com.routinely.proto.challenge.ChallengeGrpcServiceGrpc;
import com.routinely.proto.challenge.ChallengeLifecycleStatus;
import com.routinely.proto.challenge.CheckMembershipRequest;
import com.routinely.proto.challenge.CheckMembershipResponse;
import com.routinely.proto.challenge.GetChallengeContextRequest;
import com.routinely.proto.challenge.GetChallengeContextResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.grpc.server.service.GrpcService;

/**
 * challenge-service gRPC 서버 어댑터.
 * 비즈니스 판단(멤버 아님, 챌린지 종료 등)은 응답 필드로 전달하고,
 * gRPC Status는 챌린지 없음(NOT_FOUND) / 내부 오류(INTERNAL)에만 사용한다. (grpc-spec.md 오류 처리 원칙)
 */
@Slf4j
@GrpcService
public class ChallengeGrpcServer extends ChallengeGrpcServiceGrpc.ChallengeGrpcServiceImplBase {

    private final ChallengeMembershipQueryService membershipQueryService;

    public ChallengeGrpcServer(ChallengeMembershipQueryService membershipQueryService) {
        this.membershipQueryService = membershipQueryService;
    }

    @Override
    public void checkMembership(CheckMembershipRequest request,
                                StreamObserver<CheckMembershipResponse> responseObserver) {
        try {
            MembershipCheckResult result = membershipQueryService.checkMembership(
                    request.getChallengeId(), request.getUserId());

            CheckMembershipResponse response = CheckMembershipResponse.newBuilder()
                    .setIsActiveMember(result.activeMember())
                    .setMemberRole(result.memberRole())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(toStatusException("CheckMembership", e));
        }
    }

    @Override
    public void getChallengeContext(GetChallengeContextRequest request,
                                    StreamObserver<GetChallengeContextResponse> responseObserver) {
        try {
            ChallengeContextResult result = membershipQueryService.getChallengeContext(
                    request.getChallengeId(), request.getUserId());

            GetChallengeContextResponse response = GetChallengeContextResponse.newBuilder()
                    .setChallengeStatus(ChallengeLifecycleStatus.valueOf(result.challengeStatus().name()))
                    .setIsMemberActive(result.memberActive())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(toStatusException("GetChallengeContext", e));
        }
    }

    private StatusRuntimeException toStatusException(String rpcName, Exception e) {
        if (e instanceof BusinessException businessException
                && businessException.getErrorCode().getStatus() == ErrorStatus.NOT_FOUND) {
            log.warn("[gRPC] {} - {}", rpcName, businessException.getMessage());
            return Status.NOT_FOUND
                    .withDescription(businessException.getErrorCode().getCode())
                    .asRuntimeException();
        }

        log.error("[gRPC] {} 처리 중 오류 발생", rpcName, e);
        return Status.INTERNAL
                .withDescription("내부 오류가 발생했습니다.")
                .asRuntimeException();
    }
}
