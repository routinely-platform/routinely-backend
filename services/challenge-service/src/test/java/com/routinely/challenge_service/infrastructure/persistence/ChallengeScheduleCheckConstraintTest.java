package com.routinely.challenge_service.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code ck_challenges_schedule}이 routine-service {@code ck_rt_schedule}과 같은 입력을 같게 판정하는지 고정한다.
 *
 * <p>⚠️ 입력 목록은 routine-service {@code RoutineTemplateScheduleCheckConstraintTest}와 <b>글자 그대로 같아야</b> 한다.
 * 세 CHECK 제약은 짝인데 컴파일러가 결합을 잡지 못한다 — 이 두 테스트가 유일한 안전망이다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17-alpine:///schedule_check_db",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@DisplayName("ck_challenges_schedule — 스케줄 CHECK 제약")
class ChallengeScheduleCheckConstraintTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @ParameterizedTest(name = "{0} / days={1} / count={2} → 허용 {3}")
    @CsvSource({
            "DAILY,         ,    , true",
            "DAILY,        1,    , false",
            "DAILY,        0,    , false",
            "DAILY,         ,   1, false",
            "SPECIFIC_DAYS, 1,    , true",
            "SPECIFIC_DAYS, 21,   , true",
            "SPECIFIC_DAYS, 127,  , true",
            "SPECIFIC_DAYS,  ,    , false",
            "SPECIFIC_DAYS, 0,    , false",
            "SPECIFIC_DAYS, 128,  , false",
            "SPECIFIC_DAYS, 21,  1, false",
            "WEEKLY_COUNT,  ,    1, true",
            "WEEKLY_COUNT,  ,    6, true",
            "WEEKLY_COUNT,  ,    0, false",
            "WEEKLY_COUNT,  ,    7, false",
            "WEEKLY_COUNT,  ,     , false",
            "WEEKLY_COUNT, 1,    3, false",
            "MONTHLY_COUNT, ,    1, true",
            "MONTHLY_COUNT, ,   28, true",
            "MONTHLY_COUNT, ,    0, false",
            "MONTHLY_COUNT, ,   29, false",
            "MONTHLY_COUNT, ,     , false",
            "MONTHLY_COUNT, 1,   3, false",
            "UNKNOWN,       ,     , false"
    })
    @DisplayName("유형별 요일·목표 횟수 조합을 routine-service 제약과 같게 판정한다")
    void insert_scheduleCombination_judgedLikeRoutineTemplate(String scheduleType, Short daysOfWeek,
                                                              Integer targetCount, boolean allowed) {
        Runnable insert = () -> jdbcTemplate.update("""
                INSERT INTO challenges (creator_user_id, title, max_members, status, category_code,
                                        started_at, ended_at, routine_title, schedule_type, days_of_week, target_count)
                VALUES (1, '챌린지', 10, 'WAITING', 'HEALTH', DATE '2026-11-01', DATE '2026-11-30',
                        '루틴', ?, ?, ?)
                """, scheduleType, daysOfWeek, targetCount);

        if (allowed) {
            assertThatCode(insert::run).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(insert::run).isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
