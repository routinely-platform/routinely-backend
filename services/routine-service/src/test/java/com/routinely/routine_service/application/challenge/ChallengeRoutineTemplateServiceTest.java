package com.routinely.routine_service.application.challenge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.routinely.core.exception.BusinessException;
import com.routinely.routine_service.domain.inbox.InboxStatus;
import com.routinely.routine_service.domain.inbox.RoutineInbox;
import com.routinely.routine_service.domain.inbox.RoutineInboxRepository;
import com.routinely.routine_service.domain.template.RoutineTemplate;
import com.routinely.routine_service.domain.template.RoutineTemplateRepository;
import com.routinely.routine_service.domain.template.ScheduleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ChallengeRoutineTemplateService")
class ChallengeRoutineTemplateServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    private static final String PAYLOAD = """
            {"eventId":"evt-1","occurredAt":"2026-06-22T00:00:00Z","challengeId":42,\
            "creatorUserId":7,"categoryCode":"EXERCISE","routineTitle":"아침 러닝 30분",\
            "scheduleType":"WEEKLY_COUNT","targetCount":3,"startedAt":"2026-06-25","endedAt":"2026-07-24"}""";

    private RoutineInboxRepository inboxRepository;
    private RoutineTemplateRepository templateRepository;
    private ChallengeRoutineTemplateService service;

    @BeforeEach
    void setUp() {
        inboxRepository = mock(RoutineInboxRepository.class);
        templateRepository = mock(RoutineTemplateRepository.class);
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        service = new ChallengeRoutineTemplateService(
                inboxRepository, templateRepository, objectMapper, FIXED_CLOCK);
    }

    private RoutineInbox receivedInbox() {
        RoutineInbox inbox = RoutineInbox.received("evt-1", "challenge.created", PAYLOAD, "CHALLENGE", 42L);
        ReflectionTestUtils.setField(inbox, "id", 1L);
        return inbox;
    }

    private RoutineInbox receivedInbox(String scheduleType, String targetCountJson) {
        String payload = """
                {"eventId":"evt-1","occurredAt":"2026-06-22T00:00:00Z","challengeId":42,\
                "creatorUserId":7,"categoryCode":"EXERCISE","routineTitle":"아침 러닝 30분",\
                "scheduleType":"%s","targetCount":%s,"startedAt":"2026-06-25","endedAt":"2026-07-24"}"""
                .formatted(scheduleType, targetCountJson);
        RoutineInbox inbox = RoutineInbox.received("evt-1", "challenge.created", payload, "CHALLENGE", 42L);
        ReflectionTestUtils.setField(inbox, "id", 1L);
        return inbox;
    }

    @Test
    @DisplayName("RECEIVED_메시지를_처리하면_챌린지_템플릿을_생성하고_PROCESSED로_전이한다")
    void processInbox_whenReceived_createsTemplateAndMarksProcessed() {
        RoutineInbox inbox = receivedInbox();
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));
        when(templateRepository.existsByChallengeId(42L)).thenReturn(false);

        service.processInbox(1L);

        ArgumentCaptor<RoutineTemplate> captor = ArgumentCaptor.forClass(RoutineTemplate.class);
        verify(templateRepository).save(captor.capture());
        RoutineTemplate template = captor.getValue();
        assertThat(template.getUserId()).isEqualTo(7L);
        assertThat(template.getChallengeId()).isEqualTo(42L);
        assertThat(template.getTitle()).isEqualTo("아침 러닝 30분");
        assertThat(template.getCategoryCode()).isEqualTo("EXERCISE");
        assertThat(template.getScheduleType()).isEqualTo(ScheduleType.WEEKLY_COUNT);
        assertThat(template.getTargetCount()).isEqualTo(3);
        assertThat(template.getDaysOfWeek()).isNull();

        assertThat(inbox.getStatus()).isEqualTo(InboxStatus.PROCESSED);
        assertThat(inbox.getProcessedAt()).isEqualTo(LocalDateTime.now(FIXED_CLOCK));
    }

    // 스케줄 정합성 위반 케이스 — 챌린지 SPECIFIC_DAYS 금지 / 빈도 유형 targetCount 누락 / DAILY targetCount 존재.
    @ParameterizedTest(name = "scheduleType={0}, targetCount={1}이면 예외를 던지고 저장하지 않는다")
    @CsvSource({
            "SPECIFIC_DAYS, null",
            "WEEKLY_COUNT, null",
            "DAILY, 3"
    })
    @DisplayName("스케줄_정합성을_위반한_이벤트는_예외를_던지고_저장하지_않는다")
    void processInbox_whenInvalidSchedule_throwsAndDoesNotSave(String scheduleType, String targetCountJson) {
        RoutineInbox inbox = receivedInbox(scheduleType, targetCountJson);
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));
        when(templateRepository.existsByChallengeId(42L)).thenReturn(false);

        assertThatThrownBy(() -> service.processInbox(1L)).isInstanceOf(BusinessException.class);

        verify(templateRepository, never()).save(any(RoutineTemplate.class));
    }

    @Test
    @DisplayName("동일_challengeId_템플릿이_이미_존재하면_재생성하지_않고_PROCESSED로_전이한다")
    void processInbox_whenTemplateExists_skipsCreationButMarksProcessed() {
        RoutineInbox inbox = receivedInbox();
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));
        when(templateRepository.existsByChallengeId(42L)).thenReturn(true);

        service.processInbox(1L);

        verify(templateRepository, never()).save(any(RoutineTemplate.class));
        assertThat(inbox.getStatus()).isEqualTo(InboxStatus.PROCESSED);
    }

    @Test
    @DisplayName("이미_처리된_메시지는_템플릿_생성도_상태전이도_하지_않는다")
    void processInbox_whenNotReceived_doesNothing() {
        RoutineInbox inbox = receivedInbox();
        inbox.markProcessed(LocalDateTime.now(FIXED_CLOCK));
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));

        service.processInbox(1L);

        verify(templateRepository, never()).existsByChallengeId(anyLong());
        verify(templateRepository, never()).save(any(RoutineTemplate.class));
    }

    @Test
    @DisplayName("recordFailure는_재시도_한도_이내면_retryCount를_올리고_RECEIVED를_유지한다")
    void recordFailure_whenWithinRetryLimit_incrementsRetryCountAndKeepsReceived() {
        RoutineInbox inbox = receivedInbox();
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));

        service.recordFailure(1L, "일시적 DB 오류");

        assertThat(inbox.getRetryCount()).isEqualTo(1);
        assertThat(inbox.getStatus()).isEqualTo(InboxStatus.RECEIVED);
        assertThat(inbox.getLastError()).isEqualTo("일시적 DB 오류");
    }

    @Test
    @DisplayName("recordFailure는_재시도_한도를_초과하면_FAILED로_전이한다")
    void recordFailure_whenExceedsRetryLimit_marksFailed() {
        RoutineInbox inbox = receivedInbox();
        ReflectionTestUtils.setField(inbox, "retryCount", ChallengeRoutineTemplateService.MAX_RETRY);
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));

        service.recordFailure(1L, "영구 실패");

        assertThat(inbox.getRetryCount()).isEqualTo(ChallengeRoutineTemplateService.MAX_RETRY + 1);
        assertThat(inbox.getStatus()).isEqualTo(InboxStatus.FAILED);
    }

    @Test
    @DisplayName("recordFailure는_오류메시지가_길면_MAX_ERROR_LENGTH로_잘라_저장한다")
    void recordFailure_whenErrorTooLong_truncates() {
        RoutineInbox inbox = receivedInbox();
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));
        String longMessage = "x".repeat(ChallengeRoutineTemplateService.MAX_ERROR_LENGTH + 500);

        service.recordFailure(1L, longMessage);

        assertThat(inbox.getLastError()).hasSize(ChallengeRoutineTemplateService.MAX_ERROR_LENGTH);
    }
}
