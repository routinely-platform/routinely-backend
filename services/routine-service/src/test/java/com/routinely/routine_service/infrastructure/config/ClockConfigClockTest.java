package com.routinely.routine_service.infrastructure.config;

import com.routinely.core.time.RoutinelyClocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 Clock 빈이 마이크로초 Clock인지 본다(#213). 정밀도 왕복 테스트는 자체 Clock 빈을 쓰므로
 * 운영 설정에서 감싸기를 빠뜨리는 회귀는 여기서 잡는다. 시계 정밀도(macOS는 원래 µs)에 기대지 않도록
 * "이미 감싼 Clock이면 micros()가 같은 객체를 돌려준다"로 판정한다.
 */
@DisplayName("ClockConfig — 운영 Clock 빈")
class ClockConfigClockTest {

    @Test
    @DisplayName("마이크로초 Clock이고 시간대는 그대로다")
    void clock_isMicrosClockInExpectedZone() {
        Clock clock = new ClockConfig().clock();

        assertThat(RoutinelyClocks.micros(clock)).isSameAs(clock);
        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
    }
}
