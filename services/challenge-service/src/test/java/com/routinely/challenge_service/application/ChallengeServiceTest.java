package com.routinely.challenge_service.application;

import com.routinely.challenge_service.application.dto.ChallengeListResult;
import com.routinely.challenge_service.application.dto.ChallengeResult;
import com.routinely.challenge_service.application.dto.CreateChallengeCommand;
import com.routinely.challenge_service.application.dto.UpdateChallengeCommand;
import com.routinely.challenge_service.domain.challenge.Challenge;
import com.routinely.challenge_service.domain.challenge.ChallengeLifecycleStatus;
import com.routinely.challenge_service.domain.challenge.ChallengeRepository;
import com.routinely.challenge_service.domain.member.ChallengeMember;
import com.routinely.challenge_service.domain.member.ChallengeMemberRepository;
import com.routinely.challenge_service.domain.member.ChallengeMemberRole;
import com.routinely.challenge_service.domain.member.MemberCountProjection;
import com.routinely.challenge_service.domain.member.MembershipStatus;
import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ChallengeService")
class ChallengeServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-05-24T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    private ChallengeRepository challengeRepository;
    private ChallengeMemberRepository challengeMemberRepository;
    private ChallengeService challengeService;

    @BeforeEach
    void setUp() {
        challengeRepository = mock(ChallengeRepository.class);
        challengeMemberRepository = mock(ChallengeMemberRepository.class);
        challengeService = new ChallengeService(challengeRepository, challengeMemberRepository, FIXED_CLOCK);
    }

    @Test
    @DisplayName("챌린지생성_비공개이면_초대코드를만들고_생성자를LEADER로저장한다")
    void createChallenge_whenPrivate_createsInviteCodeAndLeader() {
        when(challengeRepository.save(any(Challenge.class))).thenAnswer(invocation -> {
            Challenge challenge = invocation.getArgument(0);
            ReflectionTestUtils.setField(challenge, "id", 1L);
            ReflectionTestUtils.setField(challenge, "createdAt", LocalDateTime.now(FIXED_CLOCK));
            return challenge;
        });

        CreateChallengeCommand command = new CreateChallengeCommand(
                "30일 러닝 챌린지",
                "매일 달리기",
                false,
                10,
                "EXERCISE",
                LocalDate.of(2026, 5, 25),
                LocalDate.of(2026, 6, 24)
        );

        ChallengeResult result = challengeService.createChallenge(100L, command);

        ArgumentCaptor<Challenge> challengeCaptor = ArgumentCaptor.forClass(Challenge.class);
        verify(challengeRepository).save(challengeCaptor.capture());
        assertThat(challengeCaptor.getValue().getCreatorUserId()).isEqualTo(100L);
        assertThat(challengeCaptor.getValue().getTitle()).isEqualTo("30일 러닝 챌린지");
        assertThat(challengeCaptor.getValue().getDescription()).isEqualTo("매일 달리기");
        assertThat(challengeCaptor.getValue().isPublic()).isFalse();
        assertThat(challengeCaptor.getValue().getInviteCode()).hasSize(20);
        assertThat(challengeCaptor.getValue().getMaxMembers()).isEqualTo(10);
        assertThat(challengeCaptor.getValue().getCategoryCode()).isEqualTo("EXERCISE");
        assertThat(challengeCaptor.getValue().getStartedAt()).isEqualTo(LocalDate.of(2026, 5, 25));
        assertThat(challengeCaptor.getValue().getEndedAt()).isEqualTo(LocalDate.of(2026, 6, 24));

        ArgumentCaptor<ChallengeMember> memberCaptor = ArgumentCaptor.forClass(ChallengeMember.class);
        verify(challengeMemberRepository).save(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getChallenge()).isSameAs(challengeCaptor.getValue());
        assertThat(memberCaptor.getValue().getUserId()).isEqualTo(100L);
        assertThat(memberCaptor.getValue().getRole()).isEqualTo(ChallengeMemberRole.LEADER);
        assertThat(memberCaptor.getValue().getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(memberCaptor.getValue().getJoinedAt()).isEqualTo(LocalDateTime.now(FIXED_CLOCK));

        assertThat(result.challengeId()).isEqualTo(1L);
        assertThat(result.inviteCode()).hasSize(20);
        assertThat(result.currentMembers()).isEqualTo(1);
        assertThat(result.myRole()).isEqualTo(ChallengeMemberRole.LEADER);
    }

    @Test
    @DisplayName("챌린지생성_공개이면_초대코드를만들지않는다")
    void createChallenge_whenPublic_doesNotCreateInviteCode() {
        when(challengeRepository.save(any(Challenge.class))).thenAnswer(invocation -> {
            Challenge challenge = invocation.getArgument(0);
            ReflectionTestUtils.setField(challenge, "id", 1L);
            ReflectionTestUtils.setField(challenge, "createdAt", LocalDateTime.now(FIXED_CLOCK));
            return challenge;
        });

        CreateChallengeCommand command = new CreateChallengeCommand(
                "30일 러닝 챌린지",
                "매일 달리기",
                true,
                10,
                "EXERCISE",
                LocalDate.of(2026, 5, 25),
                LocalDate.of(2026, 6, 24)
        );

        ChallengeResult result = challengeService.createChallenge(100L, command);

        ArgumentCaptor<Challenge> challengeCaptor = ArgumentCaptor.forClass(Challenge.class);
        verify(challengeRepository).save(challengeCaptor.capture());
        assertThat(challengeCaptor.getValue().isPublic()).isTrue();
        assertThat(challengeCaptor.getValue().getInviteCode()).isNull();
        assertThat(result.inviteCode()).isNull();
    }

    @Test
    @DisplayName("공개챌린지목록조회_Page기반메타데이터와_활성멤버수를반환한다")
    void getPublicChallenges_returnsPageMetadataAndMemberCounts() {
        Pageable pageable = PageRequest.of(1, 2);
        Challenge first = createChallenge(1L, "아침 운동", true, null, ChallengeLifecycleStatus.WAITING);
        Challenge second = createChallenge(2L, "저녁 독서", true, null, ChallengeLifecycleStatus.WAITING);
        when(challengeRepository.findJoinablePublicChallenges(
                eq(100L),
                eq(ChallengeLifecycleStatus.WAITING),
                any(),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(first, second), pageable, 5));
        when(challengeMemberRepository.countMembersByChallengeIdsAndStatus(
                List.of(1L, 2L),
                MembershipStatus.ACTIVE
        )).thenReturn(List.of(memberCount(1L, 3L), memberCount(2L, 1L)));

        ChallengeListResult result = challengeService.getPublicChallenges(100L, pageable);

        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(5);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.content()).extracting(ChallengeResult::challengeId).containsExactly(1L, 2L);
        assertThat(result.content()).extracting(ChallengeResult::currentMembers).containsExactly(3, 1);
        assertThat(result.content()).extracting(ChallengeResult::inviteCode).containsOnlyNulls();
        assertThat(result.content()).extracting(ChallengeResult::myRole).containsOnlyNulls();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<MembershipStatus>> excludedStatusesCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(challengeRepository).findJoinablePublicChallenges(
                eq(100L),
                eq(ChallengeLifecycleStatus.WAITING),
                excludedStatusesCaptor.capture(),
                eq(pageable)
        );
        assertThat(excludedStatusesCaptor.getValue())
                .containsExactlyInAnyOrder(MembershipStatus.ACTIVE, MembershipStatus.EXPELLED);
    }

    @Test
    @DisplayName("내참여챌린지목록조회_Page기반메타데이터와_myRole을반환한다")
    void getMyJoinedChallenges_returnsPageMetadataAndMyRole() {
        Pageable pageable = PageRequest.of(0, 2);
        Challenge first = createChallenge(1L, "아침 운동", true, null, ChallengeLifecycleStatus.WAITING);
        Challenge second = createChallenge(2L, "저녁 독서", false, "invite-code", ChallengeLifecycleStatus.WAITING);
        ChallengeMember leader = createMember(first, 100L, ChallengeMemberRole.LEADER);
        ChallengeMember member = createMember(second, 100L, ChallengeMemberRole.MEMBER);
        when(challengeMemberRepository.findByUserIdAndStatus(100L, MembershipStatus.ACTIVE, pageable))
                .thenReturn(new PageImpl<>(List.of(leader, member), pageable, 2));
        when(challengeMemberRepository.countMembersByChallengeIdsAndStatus(
                List.of(1L, 2L),
                MembershipStatus.ACTIVE
        )).thenReturn(List.of(memberCount(1L, 3L), memberCount(2L, 2L)));

        ChallengeListResult result = challengeService.getMyJoinedChallenges(100L, pageable);

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.content()).extracting(ChallengeResult::challengeId).containsExactly(1L, 2L);
        assertThat(result.content()).extracting(ChallengeResult::currentMembers).containsExactly(3, 2);
        assertThat(result.content()).extracting(ChallengeResult::myRole)
                .containsExactly(ChallengeMemberRole.LEADER, ChallengeMemberRole.MEMBER);
    }

    @Test
    @DisplayName("챌린지상세조회_존재하지않으면_예외를던진다")
    void getChallengeDetail_whenChallengeNotFound_throwsException() {
        when(challengeRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> challengeService.getChallengeDetail(1L, 100L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHALLENGE_NOT_FOUND);
                    assertThat(exception.getMessage()).isEqualTo("챌린지를 찾을 수 없습니다.");
                });
    }

    @Test
    @DisplayName("챌린지상세조회_활성참여자가아니면_예외를던진다")
    void getChallengeDetail_whenNotActiveMember_throwsException() {
        Challenge challenge = createChallenge(1L, "비공개 챌린지", false, "invite-code", ChallengeLifecycleStatus.WAITING);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(
                1L,
                100L,
                MembershipStatus.ACTIVE
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> challengeService.getChallengeDetail(1L, 100L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_CHALLENGE_MEMBER);
                    assertThat(exception.getMessage()).isEqualTo("챌린지 멤버가 아닙니다.");
                });

        verify(challengeMemberRepository, never()).countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE);
    }

    @Test
    @DisplayName("챌린지상세조회_비공개챌린지의LEADER이면_초대코드를반환한다")
    void getChallengeDetail_whenPrivateLeader_returnsInviteCode() {
        Challenge challenge = createChallenge(1L, "비공개 챌린지", false, "private-invite-code", ChallengeLifecycleStatus.WAITING);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(4);

        ChallengeResult result = challengeService.getChallengeDetail(1L, 100L);

        assertThat(result.inviteCode()).isEqualTo("private-invite-code");
        assertThat(result.currentMembers()).isEqualTo(4);
        assertThat(result.myRole()).isEqualTo(ChallengeMemberRole.LEADER);
    }

    @Test
    @DisplayName("챌린지상세조회_공개챌린지는LEADER여도_초대코드를반환하지않는다")
    void getChallengeDetail_whenPublicLeader_hidesInviteCode() {
        Challenge challenge = createChallenge(1L, "공개 챌린지", true, null, ChallengeLifecycleStatus.WAITING);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(4);

        ChallengeResult result = challengeService.getChallengeDetail(1L, 100L);

        assertThat(result.inviteCode()).isNull();
        assertThat(result.myRole()).isEqualTo(ChallengeMemberRole.LEADER);
    }

    @Test
    @DisplayName("챌린지상세조회_비공개챌린지의일반멤버이면_초대코드를숨긴다")
    void getChallengeDetail_whenPrivateMember_hidesInviteCode() {
        Challenge challenge = createChallenge(1L, "비공개 챌린지", false, "private-invite-code", ChallengeLifecycleStatus.WAITING);
        ChallengeMember member = createMember(challenge, 100L, ChallengeMemberRole.MEMBER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(member));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(4);

        ChallengeResult result = challengeService.getChallengeDetail(1L, 100L);

        assertThat(result.inviteCode()).isNull();
        assertThat(result.myRole()).isEqualTo(ChallengeMemberRole.MEMBER);
    }

    @Test
    @DisplayName("챌린지수정_존재하지않으면_예외를던진다")
    void updateChallenge_whenChallengeNotFound_throwsException() {
        when(challengeRepository.findById(1L)).thenReturn(Optional.empty());

        UpdateChallengeCommand command = updateCommand("새 제목", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> challengeService.updateChallenge(1L, 100L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CHALLENGE_NOT_FOUND);
                    assertThat(exception.getMessage()).isEqualTo("챌린지를 찾을 수 없습니다.");
                });
    }

    @Test
    @DisplayName("챌린지수정_방장혼자일때_모든필드를수정할수있다")
    void updateChallenge_soloLeader_success() {
        Challenge challenge = createChallenge(1L, "기존 제목", false, "private-invite-code", ChallengeLifecycleStatus.WAITING);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(1);

        UpdateChallengeCommand command = updateCommand(
                "새 제목",
                "새 설명",
                true,
                5,
                LocalDate.of(2026, 5, 26),
                LocalDate.of(2026, 6, 25),
                null,
                null
        );

        ChallengeResult result = challengeService.updateChallenge(1L, 100L, command);

        assertThat(challenge.getTitle()).isEqualTo("새 제목");
        assertThat(challenge.getDescription()).isEqualTo("새 설명");
        assertThat(challenge.isPublic()).isTrue();
        assertThat(challenge.getInviteCode()).isEqualTo("private-invite-code");
        assertThat(challenge.getMaxMembers()).isEqualTo(5);
        assertThat(challenge.getStartedAt()).isEqualTo(LocalDate.of(2026, 5, 26));
        assertThat(challenge.getEndedAt()).isEqualTo(LocalDate.of(2026, 6, 25));
        assertThat(result.title()).isEqualTo("새 제목");
        assertThat(result.description()).isEqualTo("새 설명");
        assertThat(result.isPublic()).isTrue();
        assertThat(result.inviteCode()).isNull();
        assertThat(result.currentMembers()).isEqualTo(1);
        assertThat(result.myRole()).isEqualTo(ChallengeMemberRole.LEADER);
    }

    @Test
    @DisplayName("챌린지수정_멤버가2명이상이면_설명만수정할수있다")
    void updateChallenge_withMultipleMembers_descriptionOnlySucceeds() {
        Challenge challenge = createChallenge(1L, "기존 제목", true, null, ChallengeLifecycleStatus.WAITING);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(3);

        UpdateChallengeCommand command = updateCommand(null, "새 설명", null, null, null, null, null, null);

        ChallengeResult result = challengeService.updateChallenge(1L, 100L, command);

        assertThat(challenge.getDescription()).isEqualTo("새 설명");
        assertThat(challenge.getTitle()).isEqualTo("기존 제목");
        assertThat(result.description()).isEqualTo("새 설명");
        assertThat(result.currentMembers()).isEqualTo(3);
        assertThat(result.myRole()).isEqualTo(ChallengeMemberRole.LEADER);
    }

    @Test
    @DisplayName("챌린지수정_멤버가2명이상이면_최대인원을늘릴수있다")
    void updateChallenge_withMultipleMembers_whenMaxMembersIncreased_succeeds() {
        Challenge challenge = createChallenge(1L, "기존 제목", true, null, ChallengeLifecycleStatus.WAITING);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(3);

        UpdateChallengeCommand command = updateCommand(null, null, null, 15, null, null, null, null);

        ChallengeResult result = challengeService.updateChallenge(1L, 100L, command);

        assertThat(challenge.getMaxMembers()).isEqualTo(15);
        assertThat(result.currentMembers()).isEqualTo(3);
        assertThat(result.myRole()).isEqualTo(ChallengeMemberRole.LEADER);
    }

    @Test
    @DisplayName("챌린지수정_ACTIVE상태에서_비공개필드수정이면_예외를던진다")
    void updateChallenge_whenNonVisibilityFieldChangedInActive_throwsException() {
        Challenge challenge = createChallenge(1L, "진행 중 챌린지", true, null, ChallengeLifecycleStatus.ACTIVE);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));

        UpdateChallengeCommand command = updateCommand("새 제목", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> challengeService.updateChallenge(1L, 100L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.getMessage()).isEqualTo("대기 중인 챌린지만 수정할 수 있습니다.");
                });

        verify(challengeMemberRepository, never()).countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE);
    }

    @Test
    @DisplayName("챌린지수정_ENDED상태이면_예외를던진다")
    void updateChallenge_whenChallengeIsEnded_throwsException() {
        Challenge challenge = createChallenge(1L, "종료된 챌린지", true, null, ChallengeLifecycleStatus.ENDED);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));

        UpdateChallengeCommand command = updateCommand("새 제목", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> challengeService.updateChallenge(1L, 100L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.getMessage()).isEqualTo("대기 중인 챌린지만 수정할 수 있습니다.");
                });

        verify(challengeMemberRepository, never()).countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE);
    }

    @Test
    @DisplayName("챌린지수정_루틴정보수정요청이면_아직지원하지않는다는예외를던진다")
    void updateChallenge_whenRoutineFieldsProvided_throwsException() {
        Challenge challenge = createChallenge(1L, "대기 챌린지", true, null, ChallengeLifecycleStatus.WAITING);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));

        UpdateChallengeCommand command = updateCommand(
                null,
                null,
                null,
                null,
                null,
                null,
                "아침 달리기",
                null
        );

        assertThatThrownBy(() -> challengeService.updateChallenge(1L, 100L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.getMessage()).isEqualTo("루틴 정보 수정은 아직 지원하지 않습니다.");
                });

        verify(challengeMemberRepository, never()).countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE);
    }

    @Test
    @DisplayName("챌린지수정_일반멤버가루틴정보수정요청을해도_권한예외를먼저던진다")
    void updateChallenge_whenMemberProvidesRoutineFields_throwsForbiddenFirst() {
        Challenge challenge = createChallenge(1L, "대기 챌린지", true, null, ChallengeLifecycleStatus.WAITING);
        ChallengeMember member = createMember(challenge, 100L, ChallengeMemberRole.MEMBER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(member));

        UpdateChallengeCommand command = updateCommand(null, null, null, null, null, null, "아침 달리기", null);

        assertThatThrownBy(() -> challengeService.updateChallenge(1L, 100L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(exception.getMessage()).isEqualTo("챌린지 방장만 수정할 수 있습니다.");
                });
    }

    @Test
    @DisplayName("챌린지수정_일반멤버이면_권한예외를던진다")
    void updateChallenge_whenMemberIsNotLeader_throwsForbidden() {
        Challenge challenge = createChallenge(1L, "대기 챌린지", true, null, ChallengeLifecycleStatus.WAITING);
        ChallengeMember member = createMember(challenge, 100L, ChallengeMemberRole.MEMBER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(member));

        UpdateChallengeCommand command = updateCommand(
                "새 제목",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> challengeService.updateChallenge(1L, 100L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(exception.getMessage()).isEqualTo("챌린지 방장만 수정할 수 있습니다.");
                });
    }

    @Test
    @DisplayName("챌린지수정_챌린지멤버가아니면_예외를던진다")
    void updateChallenge_whenNotChallengeMember_throwsException() {
        Challenge challenge = createChallenge(1L, "대기 챌린지", true, null, ChallengeLifecycleStatus.WAITING);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.empty());

        UpdateChallengeCommand command = updateCommand("새 제목", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> challengeService.updateChallenge(1L, 100L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_CHALLENGE_MEMBER);
                    assertThat(exception.getMessage()).isEqualTo("챌린지 멤버가 아닙니다.");
                });

        verify(challengeMemberRepository, never()).countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE);
    }

    @Test
    @DisplayName("챌린지수정_리더검증후_활성멤버수가0이면_예외를던진다")
    void updateChallenge_whenActiveMemberCountIsInvalid_throwsException() {
        Challenge challenge = createChallenge(1L, "대기 챌린지", true, null, ChallengeLifecycleStatus.WAITING);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(0);

        UpdateChallengeCommand command = updateCommand(null, "새 설명", null, null, null, null, null, null);

        assertThatThrownBy(() -> challengeService.updateChallenge(1L, 100L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR);
                    assertThat(exception.getMessage()).isEqualTo("챌린지 멤버 상태가 올바르지 않습니다.");
                });
        assertThat(challenge.getDescription()).isEqualTo("설명");
    }

    @Test
    @DisplayName("챌린지수정_방장혼자이면_공개에서비공개로전환하면_초대코드가자동생성된다")
    void updateChallenge_soloLeader_whenPublicToPrivate_inviteCodeIsGenerated() {
        Challenge challenge = createChallenge(1L, "공개 챌린지", true, null, ChallengeLifecycleStatus.WAITING);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(1);

        UpdateChallengeCommand command = updateCommand(null, null, false, null, null, null, null, null);

        ChallengeResult result = challengeService.updateChallenge(1L, 100L, command);

        assertThat(challenge.isPublic()).isFalse();
        assertThat(challenge.getInviteCode()).isNotNull().hasSize(20);
        assertThat(result.isPublic()).isFalse();
        assertThat(result.inviteCode()).isNotNull().hasSize(20);
        assertThat(result.myRole()).isEqualTo(ChallengeMemberRole.LEADER);
    }

    @Test
    @DisplayName("챌린지수정_최대인원이현재멤버수보다작으면_예외를던진다")
    void updateChallenge_whenMaxMembersLessThanCurrentMembers_throwsException() {
        Challenge challenge = createChallenge(1L, "대기 챌린지", true, null, ChallengeLifecycleStatus.WAITING);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(4);

        UpdateChallengeCommand command = updateCommand(null, null, null, 3, null, null, null, null);

        assertThatThrownBy(() -> challengeService.updateChallenge(1L, 100L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.getMessage()).isEqualTo("다른 멤버가 있어 최대 인원을 줄일 수 없습니다.");
                });
        assertThat(challenge.getMaxMembers()).isEqualTo(10);
    }

    @Test
    @DisplayName("챌린지수정_시작일이오늘보다이전이면_예외를던진다")
    void updateChallenge_whenStartedAtBeforeToday_throwsException() {
        Challenge challenge = createChallenge(1L, "대기 챌린지", true, null, ChallengeLifecycleStatus.WAITING);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(1);

        UpdateChallengeCommand command = updateCommand(
                null,
                null,
                null,
                null,
                LocalDate.of(2026, 5, 23),
                null,
                null,
                null
        );

        assertThatThrownBy(() -> challengeService.updateChallenge(1L, 100L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.getMessage()).isEqualTo("시작일은 오늘 이후여야 합니다.");
                });
    }

    @Test
    @DisplayName("챌린지수정_종료일이시작일보다빠르면_예외를던진다")
    void updateChallenge_whenEndedAtBeforeStartedAt_throwsException() {
        Challenge challenge = createChallenge(1L, "대기 챌린지", true, null, ChallengeLifecycleStatus.WAITING);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(1);

        UpdateChallengeCommand command = updateCommand(
                null,
                null,
                null,
                null,
                LocalDate.of(2026, 5, 26),
                LocalDate.of(2026, 5, 25),
                null,
                null
        );

        assertThatThrownBy(() -> challengeService.updateChallenge(1L, 100L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.getMessage()).isEqualTo("종료일은 시작일보다 빠를 수 없습니다.");
                });
    }

    @Test
    @DisplayName("챌린지수정_시작일만수정해도_기존종료일보다늦으면_예외를던진다")
    void updateChallenge_whenOnlyStartedAtAfterExistingEndedAt_throwsException() {
        Challenge challenge = createChallenge(1L, "대기 챌린지", true, null, ChallengeLifecycleStatus.WAITING);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(1);

        UpdateChallengeCommand command = updateCommand(
                null,
                null,
                null,
                null,
                LocalDate.of(2026, 6, 25),
                null,
                null,
                null
        );

        assertThatThrownBy(() -> challengeService.updateChallenge(1L, 100L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.getMessage()).isEqualTo("종료일은 시작일보다 빠를 수 없습니다.");
                });
    }

    @Test
    @DisplayName("챌린지수정_종료일만수정해도_기존시작일보다빠르면_예외를던진다")
    void updateChallenge_whenOnlyEndedAtBeforeExistingStartedAt_throwsException() {
        Challenge challenge = createChallenge(1L, "대기 챌린지", true, null, ChallengeLifecycleStatus.WAITING);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(1);

        UpdateChallengeCommand command = updateCommand(
                null,
                null,
                null,
                null,
                null,
                LocalDate.of(2026, 5, 24),
                null,
                null
        );

        assertThatThrownBy(() -> challengeService.updateChallenge(1L, 100L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.getMessage()).isEqualTo("종료일은 시작일보다 빠를 수 없습니다.");
                });
    }

    @Test
    @DisplayName("챌린지수정_방장혼자이면_비공개에서공개로전환해도_초대코드가유지된다")
    void updateChallenge_soloLeader_whenPrivateToPublic_inviteCodeRetained() {
        Challenge challenge = createChallenge(1L, "비공개 챌린지", false, "invite-code-12345678", ChallengeLifecycleStatus.WAITING);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(1);

        UpdateChallengeCommand command = updateCommand(null, null, true, null, null, null, null, null);

        ChallengeResult result = challengeService.updateChallenge(1L, 100L, command);

        assertThat(challenge.isPublic()).isTrue();
        assertThat(challenge.getInviteCode()).isEqualTo("invite-code-12345678");
        assertThat(result.isPublic()).isTrue();
        assertThat(result.inviteCode()).isNull();
        assertThat(result.myRole()).isEqualTo(ChallengeMemberRole.LEADER);
    }

    @Test
    @DisplayName("챌린지수정_ACTIVE상태에서_비공개에서공개로전환할수있다")
    void updateChallenge_active_whenPrivateToPublic_succeeds() {
        Challenge challenge = createChallenge(1L, "비공개 챌린지", false, "invite-code-12345678", ChallengeLifecycleStatus.ACTIVE);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(3);

        UpdateChallengeCommand command = updateCommand(null, null, true, null, null, null, null, null);

        ChallengeResult result = challengeService.updateChallenge(1L, 100L, command);

        assertThat(challenge.isPublic()).isTrue();
        assertThat(result.isPublic()).isTrue();
        assertThat(result.inviteCode()).isNull();
    }

    @Test
    @DisplayName("챌린지수정_멤버가2명이상이면_공개에서비공개로전환할수없다")
    void updateChallenge_withMultipleMembers_whenPublicToPrivate_throwsException() {
        Challenge challenge = createChallenge(1L, "공개 챌린지", true, null, ChallengeLifecycleStatus.WAITING);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(3);

        UpdateChallengeCommand command = updateCommand(null, null, false, null, null, null, null, null);

        assertThatThrownBy(() -> challengeService.updateChallenge(1L, 100L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.getMessage()).isEqualTo("멤버가 존재하는 챌린지는 공개 → 비공개 변경이 불가능합니다.");
                });
        assertThat(challenge.isPublic()).isTrue();
    }

    @Test
    @DisplayName("챌린지수정_ACTIVE상태에서_공개에서비공개로전환할수없다")
    void updateChallenge_active_withMultipleMembers_whenPublicToPrivate_throwsException() {
        Challenge challenge = createChallenge(1L, "공개 챌린지", true, null, ChallengeLifecycleStatus.ACTIVE);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(3);

        UpdateChallengeCommand command = updateCommand(null, null, false, null, null, null, null, null);

        assertThatThrownBy(() -> challengeService.updateChallenge(1L, 100L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.getMessage()).isEqualTo("멤버가 존재하는 챌린지는 공개 → 비공개 변경이 불가능합니다.");
                });
        assertThat(challenge.isPublic()).isTrue();
    }

    @Test
    @DisplayName("챌린지수정_ENDED상태이면_공개여부를수정할수없다")
    void updateChallenge_whenEnded_privateToPublic_throwsException() {
        Challenge challenge = createChallenge(1L, "종료된 챌린지", false, "invite-code-12345678", ChallengeLifecycleStatus.ENDED);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(1);

        UpdateChallengeCommand command = updateCommand(null, null, true, null, null, null, null, null);

        assertThatThrownBy(() -> challengeService.updateChallenge(1L, 100L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.getMessage()).isEqualTo("종료된 챌린지는 공개 여부를 수정할 수 없습니다.");
                });
    }

    @Test
    @DisplayName("챌린지수정_ENDED상태이면_공개에서비공개로전환할수없다")
    void updateChallenge_whenEnded_publicToPrivate_throwsException() {
        Challenge challenge = createChallenge(1L, "종료된 챌린지", true, null, ChallengeLifecycleStatus.ENDED);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(1);

        UpdateChallengeCommand command = updateCommand(null, null, false, null, null, null, null, null);

        assertThatThrownBy(() -> challengeService.updateChallenge(1L, 100L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.getMessage()).isEqualTo("종료된 챌린지는 공개 여부를 수정할 수 없습니다.");
                });
        assertThat(challenge.isPublic()).isTrue();
        assertThat(challenge.getInviteCode()).isNull();
    }

    @Test
    @DisplayName("챌린지수정_공개여부는허용되지만_다른필드가제한되면_어떤필드도변경하지않는다")
    void updateChallenge_whenVisibilityAllowedButNonVisibilityInvalid_doesNotMutate() {
        Challenge challenge = createChallenge(1L, "기존 제목", false, "invite-code-12345678", ChallengeLifecycleStatus.WAITING);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(3);

        UpdateChallengeCommand command = updateCommand(
                "새 제목",
                null,
                true,
                null,
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> challengeService.updateChallenge(1L, 100L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.getMessage()).isEqualTo("다른 멤버가 있어 제목을 변경할 수 없습니다.");
                });
        assertThat(challenge.isPublic()).isFalse();
        assertThat(challenge.getTitle()).isEqualTo("기존 제목");
        assertThat(challenge.getInviteCode()).isEqualTo("invite-code-12345678");
    }

    @Test
    @DisplayName("챌린지수정_멤버가2명이상이면_제목변경시_예외를던진다")
    void updateChallenge_withMultipleMembers_whenTitleChanged_throwsException() {
        Challenge challenge = createChallenge(1L, "기존 제목", true, null, ChallengeLifecycleStatus.WAITING);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(3);

        UpdateChallengeCommand command = updateCommand("새 제목", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> challengeService.updateChallenge(1L, 100L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.getMessage()).isEqualTo("다른 멤버가 있어 제목을 변경할 수 없습니다.");
                });
        assertThat(challenge.getTitle()).isEqualTo("기존 제목");
    }

    @Test
    @DisplayName("챌린지수정_멤버가2명이상이면_비공개에서공개로전환하면_초대코드가유지된다")
    void updateChallenge_withMultipleMembers_whenPrivateToPublic_inviteCodeRetained() {
        Challenge challenge = createChallenge(1L, "기존 제목", false, "invite-code-12345678", ChallengeLifecycleStatus.WAITING);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(3);

        UpdateChallengeCommand command = updateCommand(null, null, true, null, null, null, null, null);

        ChallengeResult result = challengeService.updateChallenge(1L, 100L, command);

        assertThat(challenge.isPublic()).isTrue();
        assertThat(challenge.getInviteCode()).isEqualTo("invite-code-12345678");
        assertThat(result.isPublic()).isTrue();
        assertThat(result.inviteCode()).isNull();
        assertThat(result.currentMembers()).isEqualTo(3);
        assertThat(result.myRole()).isEqualTo(ChallengeMemberRole.LEADER);
    }

    @Test
    @DisplayName("챌린지수정_멤버가2명이상이면_시작일변경시_예외를던진다")
    void updateChallenge_withMultipleMembers_whenStartedAtChanged_throwsException() {
        Challenge challenge = createChallenge(1L, "기존 제목", true, null, ChallengeLifecycleStatus.WAITING);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(3);

        UpdateChallengeCommand command = updateCommand(null, null, null, null, LocalDate.of(2026, 5, 26), null, null, null);

        assertThatThrownBy(() -> challengeService.updateChallenge(1L, 100L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.getMessage()).isEqualTo("다른 멤버가 있어 시작일을 변경할 수 없습니다.");
                });
        assertThat(challenge.getStartedAt()).isEqualTo(LocalDate.of(2026, 5, 25));
    }

    @Test
    @DisplayName("챌린지수정_멤버가2명이상이면_종료일변경시_예외를던진다")
    void updateChallenge_withMultipleMembers_whenEndedAtChanged_throwsException() {
        Challenge challenge = createChallenge(1L, "기존 제목", true, null, ChallengeLifecycleStatus.WAITING);
        ChallengeMember leader = createMember(challenge, 100L, ChallengeMemberRole.LEADER);
        when(challengeRepository.findById(1L)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(1L, 100L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(leader));
        when(challengeMemberRepository.countByChallengeIdAndStatus(1L, MembershipStatus.ACTIVE))
                .thenReturn(3);

        UpdateChallengeCommand command = updateCommand(null, null, null, null, null, LocalDate.of(2026, 7, 1), null, null);

        assertThatThrownBy(() -> challengeService.updateChallenge(1L, 100L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.getMessage()).isEqualTo("다른 멤버가 있어 종료일을 변경할 수 없습니다.");
                });
        assertThat(challenge.getEndedAt()).isEqualTo(LocalDate.of(2026, 6, 24));
    }

    private Challenge createChallenge(Long id,
                                      String title,
                                      boolean isPublic,
                                      String inviteCode,
                                      ChallengeLifecycleStatus status) {
        Challenge challenge = Challenge.builder()
                .creatorUserId(100L)
                .title(title)
                .description("설명")
                .isPublic(isPublic)
                .inviteCode(inviteCode)
                .maxMembers(10)
                .status(status)
                .categoryCode("EXERCISE")
                .startedAt(LocalDate.of(2026, 5, 25))
                .endedAt(LocalDate.of(2026, 6, 24))
                .build();
        ReflectionTestUtils.setField(challenge, "id", id);
        ReflectionTestUtils.setField(challenge, "createdAt", LocalDateTime.now(FIXED_CLOCK));
        return challenge;
    }

    private ChallengeMember createMember(Challenge challenge, Long userId, ChallengeMemberRole role) {
        return ChallengeMember.builder()
                .challenge(challenge)
                .userId(userId)
                .role(role)
                .status(MembershipStatus.ACTIVE)
                .joinedAt(LocalDateTime.now(FIXED_CLOCK))
                .build();
    }

    private MemberCountProjection memberCount(Long challengeId, Long count) {
        return new MemberCountProjection() {
            @Override
            public Long getChallengeId() {
                return challengeId;
            }

            @Override
            public Long getCount() {
                return count;
            }
        };
    }

    private UpdateChallengeCommand updateCommand(String title,
                                                 String description,
                                                 Boolean isPublic,
                                                 Integer maxMembers,
                                                 LocalDate startedAt,
                                                 LocalDate endedAt,
                                                 String routineTitle,
                                                 String routinePreferredTime) {
        return new UpdateChallengeCommand(
                title,
                description,
                isPublic,
                maxMembers,
                startedAt,
                endedAt,
                routineTitle,
                routinePreferredTime
        );
    }
}
