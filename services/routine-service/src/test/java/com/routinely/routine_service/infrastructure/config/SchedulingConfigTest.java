package com.routinely.routine_service.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

class SchedulingConfigTest {

    private static final String SCHEDULING_BEAN_NAME =
            TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class);

    @Test
    @DisplayName("설정 키가 없으면 스케줄링이 켜진다")
    void scheduling_propertyMissing_enabled() {
        runner.run(context -> assertThat(context).hasBean(SCHEDULING_BEAN_NAME));
    }

    @Test
    @DisplayName("설정이 false면 스케줄링이 꺼진다")
    void scheduling_propertyFalse_disabled() {
        runner.withPropertyValues("spring.task.scheduling.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SCHEDULING_BEAN_NAME));
    }
}
