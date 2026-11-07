package com.routinely.routine_service.application.challenge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.core.exception.BusinessException;
import com.routinely.routine_service.application.event.RoutineEventPublisher;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.routine.RoutineRepository;
import com.routinely.routine_service.domain.template.RoutineTemplateRepository;
import com.routinely.routine_service.infrastructure.grpc.ChallengeServiceGrpcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ChallengeStartedHandler")
class ChallengeStartedHandlerTest {

    private RoutineRepository routineRepository;
    private RoutineEventPublisher eventPublisher;
    private ChallengeStartedHandler handler;

    @BeforeEach
    void setUp() {
        routineRepository = mock(RoutineRepository.class);
        eventPublisher = mock(RoutineEventPublisher.class);
        handler = new ChallengeStartedHandler(routineRepository, eventPublisher, new ObjectMapper());
    }

    private static ChallengeStartedPayload payload(String field, Object value) {
        ChallengeStartedPayload base = new ChallengeStartedPayload("evt-1", "2026-11-09T00:00:00Z", 5L,
                "30일 러닝 챌린지", "2026-11-09", "2026-12-08", "저녁 러닝 30분", "EXERCISE", "SPECIFIC_DAYS",
                List.of("MON", "WED", "FRI"), null, 100L,
                List.of(new ChallengeStartedPayload.Member(100L, "2026-11-01"),
                        new ChallengeStartedPayload.Member(200L, "2026-11-02")));
        if (field == null) {
            return base;
        }
        // record는 불변이라 리플렉션으로 한 필드만 바꾼 사본을 만든다.
        Object[] args = Arrays.stream(ChallengeStartedPayload.class.getRecordComponents())
                .map(component -> component.getName().equals(field) ? value
                        : ReflectionTestUtils.invokeMethod(base, component.getName()))
                .toArray();
        try {
            return (ChallengeStartedPayload) ChallengeStartedPayload.class.getDeclaredConstructors()[0]
                    .newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("eventType은_challenge.started다")
    void eventType() {
        assertThat(handler.eventType()).isEqualTo("challenge.started");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"challengeId", "routineTitle", "categoryCode", "scheduleType", "startedAt", "endedAt"})
    @DisplayName("필수_필드가_없으면_필드명을_담아_실패하고_아무것도_저장하지_않는다")
    void createMemberRoutines_missingRequiredField_throwsException(String field) {
        assertThatThrownBy(() -> handler.createMemberRoutines(payload(field, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(field);

        verifyNoInteractions(routineRepository, eventPublisher);
    }

    @Test
    @DisplayName("members가_비어_있으면_실패한다")
    void createMemberRoutines_emptyMembers_throwsException() {
        assertThatThrownBy(() -> handler.createMemberRoutines(payload("members", List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("members");

        verifyNoInteractions(routineRepository, eventPublisher);
    }

    @Test
    @DisplayName("유형과_요일·횟수_조합이_스케줄_규칙에_어긋나면_정의_단계에서_실패한다")
    void createMemberRoutines_invalidScheduleCombination_throwsException() {
        // WEEKLY_COUNT인데 요일이 있고 목표 횟수가 없다
        assertThatThrownBy(() -> handler.createMemberRoutines(payload("scheduleType", "WEEKLY_COUNT")))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(routineRepository, eventPublisher);
    }

    @Test
    @DisplayName("요일_코드가_잘못되면_실패한다")
    void createMemberRoutines_invalidDayCode_throwsException() {
        assertThatThrownBy(() -> handler.createMemberRoutines(payload("daysOfWeek", List.of("MON", "FUNDAY"))))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(routineRepository, eventPublisher);
    }

    @Test
    @DisplayName("멤버마다_루틴을_먼저_저장하고_저장된_루틴으로_알림_예약을_발행한다")
    void createMemberRoutines_savesBeforePublishing() {
        Routine saved = mock(Routine.class);
        when(routineRepository.save(any(Routine.class))).thenReturn(saved);

        int created = handler.createMemberRoutines(payload(null, null));

        assertThat(created).isEqualTo(2);
        InOrder order = inOrder(routineRepository, eventPublisher);
        order.verify(routineRepository).save(any(Routine.class));
        order.verify(eventPublisher).publishNotificationScheduled(saved);
        order.verify(routineRepository).save(any(Routine.class));
        order.verify(eventPublisher).publishNotificationScheduled(saved);
    }

    @Test
    @DisplayName("정의를_페이로드에서만_만든다_—_템플릿_저장소·챌린지_gRPC에_의존하지_않는다")
    void handler_doesNotDependOnTemplateOrGrpc() {
        List<Class<?>> dependencies = Arrays.stream(ChallengeStartedHandler.class.getDeclaredFields())
                .<Class<?>>map(Field::getType)
                .toList();

        assertThat(dependencies).doesNotContain(RoutineTemplateRepository.class, ChallengeServiceGrpcClient.class);
    }
}
