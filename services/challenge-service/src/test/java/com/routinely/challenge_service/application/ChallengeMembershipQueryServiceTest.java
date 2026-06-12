package com.routinely.challenge_service.application;

import com.routinely.challenge_service.application.dto.ChallengeContextResult;
import com.routinely.challenge_service.application.dto.MembershipCheckResult;
import com.routinely.challenge_service.domain.challenge.Challenge;
import com.routinely.challenge_service.domain.challenge.ChallengeLifecycleStatus;
import com.routinely.challenge_service.domain.challenge.ChallengeRepository;
import com.routinely.challenge_service.domain.member.ChallengeMember;
import com.routinely.challenge_service.domain.member.ChallengeMemberRepository;
import com.routinely.challenge_service.domain.member.MembershipStatus;
import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ChallengeMembershipQueryService")
class ChallengeMembershipQueryServiceTest {

    private ChallengeRepository challengeRepository;
    private ChallengeMemberRepository challengeMemberRepository;
    private ChallengeMembershipQueryService membershipQueryService;

    @BeforeEach
    void setUp() {
        challengeRepository = mock(ChallengeRepository.class);
        challengeMemberRepository = mock(ChallengeMemberRepository.class);
        membershipQueryService = new ChallengeMembershipQueryService(challengeRepository, challengeMemberRepository);
    }

    @Test
    @DisplayName("멤버검증_ACTIVE리더이면_true와LEADER를반환한다")
    void checkMembership_whenActiveLeader_returnsTrueAndLeaderRole() {
        Challenge challenge = createChallenge(ChallengeLifecycleStatus.ACTIVE);
        ChallengeMember leader = ChallengeMember.createLeader(challenge, 100L, LocalDateTime.now());
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));

        MembershipCheckResult result = membershipQueryService.checkMembership(1L, 100L);

        assertThat(result.activeMember()).isTrue();
        assertThat(result.memberRole()).isEqualTo("LEADER");
    }

    @Test
    @DisplayName("멤버검증_ACTIVE일반멤버이면_true와MEMBER를반환한다")
    void checkMembership_whenActiveMember_returnsTrueAndMemberRole() {
        Challenge challenge = createChallenge(ChallengeLifecycleStatus.ACTIVE);
        ChallengeMember member = ChallengeMember.createMember(challenge, 200L, LocalDateTime.now());
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 200L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(member));

        MembershipCheckResult result = membershipQueryService.checkMembership(1L, 200L);

        assertThat(result.activeMember()).isTrue();
        assertThat(result.memberRole()).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("멤버검증_ACTIVE멤버가아니면_false와빈문자열을반환한다")
    void checkMembership_whenNotActiveMember_returnsFalseAndEmptyRole() {
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 300L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.empty());

        MembershipCheckResult result = membershipQueryService.checkMembership(1L, 300L);

        assertThat(result.activeMember()).isFalse();
        assertThat(result.memberRole()).isEmpty();
    }

    // ── GetChallengeContext ────────────────────────────────────────────────

    @Test
    @DisplayName("컨텍스트조회_챌린지가없으면_CHALLENGE_NOT_FOUND예외가발생한다")
    void getChallengeContext_whenChallengeNotFound_throwsNotFound() {
        when(challengeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> membershipQueryService.getChallengeContext(99L, 100L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHALLENGE_NOT_FOUND);
    }

    @Test
    @DisplayName("컨텍스트조회_ACTIVE챌린지의ACTIVE멤버이면_상태와true를반환한다")
    void getChallengeContext_whenActiveChallengeAndActiveMember_returnsActiveAndTrue() {
        Challenge challenge = createChallenge(ChallengeLifecycleStatus.ACTIVE);
        ChallengeMember member = ChallengeMember.createMember(challenge, 100L, LocalDateTime.now());
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(member));

        ChallengeContextResult result = membershipQueryService.getChallengeContext(1L, 100L);

        assertThat(result.challengeStatus()).isEqualTo(ChallengeLifecycleStatus.ACTIVE);
        assertThat(result.memberActive()).isTrue();
    }

    @Test
    @DisplayName("컨텍스트조회_종료된챌린지의탈퇴멤버이면_ENDED와false를반환한다")
    void getChallengeContext_whenEndedChallengeAndLeftMember_returnsEndedAndFalse() {
        Challenge challenge = createChallenge(ChallengeLifecycleStatus.ENDED);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.empty());

        ChallengeContextResult result = membershipQueryService.getChallengeContext(1L, 100L);

        assertThat(result.challengeStatus()).isEqualTo(ChallengeLifecycleStatus.ENDED);
        assertThat(result.memberActive()).isFalse();
    }

    private Challenge createChallenge(ChallengeLifecycleStatus status) {
        return Challenge.builder()
                .id(1L)
                .creatorUserId(100L)
                .title("30일 러닝 챌린지")
                .isPublic(true)
                .maxMembers(10)
                .status(status)
                .categoryCode("EXERCISE")
                .startedAt(LocalDate.of(2026, 5, 25))
                .endedAt(LocalDate.of(2026, 6, 24))
                .build();
    }
}
