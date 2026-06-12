package com.routinely.challenge_service.presentation.grpc;

import com.routinely.challenge_service.application.ChallengeMembershipQueryService;
import com.routinely.challenge_service.application.dto.ChallengeContextResult;
import com.routinely.challenge_service.application.dto.MembershipCheckResult;
import com.routinely.challenge_service.domain.challenge.ChallengeLifecycleStatus;
import com.routinely.challenge_service.domain.member.ChallengeMemberRole;
import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.proto.challenge.CheckMembershipRequest;
import com.routinely.proto.challenge.CheckMembershipResponse;
import com.routinely.proto.challenge.GetChallengeContextRequest;
import com.routinely.proto.challenge.GetChallengeContextResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ChallengeGrpcServer")
class ChallengeGrpcServerTest {

    private ChallengeMembershipQueryService membershipQueryService;
    private ChallengeGrpcServer grpcServer;

    @BeforeEach
    void setUp() {
        membershipQueryService = mock(ChallengeMembershipQueryService.class);
        grpcServer = new ChallengeGrpcServer(membershipQueryService);
    }

    // ── CheckMembership ────────────────────────────────────────────────

    @Test
    @DisplayName("멤버검증_ACTIVE리더이면_응답필드에true와LEADER를담아완료한다")
    void checkMembership_whenActiveLeader_respondsTrueAndLeader() {
        when(membershipQueryService.checkMembership(1L, 100L))
                .thenReturn(MembershipCheckResult.activeMember(ChallengeMemberRole.LEADER));
        StreamObserver<CheckMembershipResponse> observer = mockObserver();

        grpcServer.checkMembership(checkMembershipRequest(1L, 100L), observer);

        ArgumentCaptor<CheckMembershipResponse> captor = ArgumentCaptor.forClass(CheckMembershipResponse.class);
        verify(observer).onNext(captor.capture());
        verify(observer).onCompleted();
        verify(observer, never()).onError(any());
        assertThat(captor.getValue().getIsActiveMember()).isTrue();
        assertThat(captor.getValue().getMemberRole()).isEqualTo("LEADER");
    }

    @Test
    @DisplayName("멤버검증_멤버가아니면_false와빈문자열로완료한다")
    void checkMembership_whenNotMember_respondsFalseAndEmptyRole() {
        when(membershipQueryService.checkMembership(1L, 300L))
                .thenReturn(MembershipCheckResult.notMember());
        StreamObserver<CheckMembershipResponse> observer = mockObserver();

        grpcServer.checkMembership(checkMembershipRequest(1L, 300L), observer);

        ArgumentCaptor<CheckMembershipResponse> captor = ArgumentCaptor.forClass(CheckMembershipResponse.class);
        verify(observer).onNext(captor.capture());
        verify(observer).onCompleted();
        assertThat(captor.getValue().getIsActiveMember()).isFalse();
        assertThat(captor.getValue().getMemberRole()).isEmpty();
    }

    @Test
    @DisplayName("멤버검증_예상치못한예외이면_INTERNAL오류를전달한다")
    void checkMembership_whenUnexpectedException_respondsInternal() {
        when(membershipQueryService.checkMembership(1L, 100L))
                .thenThrow(new IllegalStateException("DB 연결 실패"));
        StreamObserver<CheckMembershipResponse> observer = mockObserver();

        grpcServer.checkMembership(checkMembershipRequest(1L, 100L), observer);

        StatusRuntimeException error = capturedError(observer);
        assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        verify(observer, never()).onNext(any());
        verify(observer, never()).onCompleted();
    }

    // ── GetChallengeContext ────────────────────────────────────────────

    @Test
    @DisplayName("컨텍스트조회_ACTIVE챌린지의ACTIVE멤버이면_proto열거형ACTIVE와true로완료한다")
    void getChallengeContext_whenActiveChallengeAndActiveMember_respondsActiveAndTrue() {
        when(membershipQueryService.getChallengeContext(1L, 100L))
                .thenReturn(new ChallengeContextResult(ChallengeLifecycleStatus.ACTIVE, true));
        StreamObserver<GetChallengeContextResponse> observer = mockObserver();

        grpcServer.getChallengeContext(contextRequest(1L, 100L), observer);

        ArgumentCaptor<GetChallengeContextResponse> captor = ArgumentCaptor.forClass(GetChallengeContextResponse.class);
        verify(observer).onNext(captor.capture());
        verify(observer).onCompleted();
        assertThat(captor.getValue().getChallengeStatus())
                .isEqualTo(com.routinely.proto.challenge.ChallengeLifecycleStatus.ACTIVE);
        assertThat(captor.getValue().getIsMemberActive()).isTrue();
    }

    @Test
    @DisplayName("컨텍스트조회_종료된챌린지의비멤버이면_ENDED와false로완료한다")
    void getChallengeContext_whenEndedChallengeAndNotMember_respondsEndedAndFalse() {
        when(membershipQueryService.getChallengeContext(1L, 100L))
                .thenReturn(new ChallengeContextResult(ChallengeLifecycleStatus.ENDED, false));
        StreamObserver<GetChallengeContextResponse> observer = mockObserver();

        grpcServer.getChallengeContext(contextRequest(1L, 100L), observer);

        ArgumentCaptor<GetChallengeContextResponse> captor = ArgumentCaptor.forClass(GetChallengeContextResponse.class);
        verify(observer).onNext(captor.capture());
        verify(observer).onCompleted();
        assertThat(captor.getValue().getChallengeStatus())
                .isEqualTo(com.routinely.proto.challenge.ChallengeLifecycleStatus.ENDED);
        assertThat(captor.getValue().getIsMemberActive()).isFalse();
    }

    @Test
    @DisplayName("컨텍스트조회_챌린지가없으면_NOT_FOUND오류와에러코드를전달한다")
    void getChallengeContext_whenChallengeNotFound_respondsNotFound() {
        when(membershipQueryService.getChallengeContext(99L, 100L))
                .thenThrow(new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        StreamObserver<GetChallengeContextResponse> observer = mockObserver();

        grpcServer.getChallengeContext(contextRequest(99L, 100L), observer);

        StatusRuntimeException error = capturedError(observer);
        assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(error.getStatus().getDescription()).isEqualTo(ErrorCode.CHALLENGE_NOT_FOUND.getCode());
        verify(observer, never()).onNext(any());
        verify(observer, never()).onCompleted();
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private <T> StreamObserver<T> mockObserver() {
        return mock(StreamObserver.class);
    }

    private StatusRuntimeException capturedError(StreamObserver<?> observer) {
        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(StatusRuntimeException.class);
        return (StatusRuntimeException) captor.getValue();
    }

    private CheckMembershipRequest checkMembershipRequest(long challengeId, long userId) {
        return CheckMembershipRequest.newBuilder()
                .setChallengeId(challengeId)
                .setUserId(userId)
                .build();
    }

    private GetChallengeContextRequest contextRequest(long challengeId, long userId) {
        return GetChallengeContextRequest.newBuilder()
                .setChallengeId(challengeId)
                .setUserId(userId)
                .build();
    }
}
