package com.routinely.core.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RoutinelyClocks")
class RoutinelyClocksTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    @DisplayName("나노초 시각을 마이크로초로 버린다 — 반올림하지 않는다")
    void micros_nanoInstant_truncatesToMicros() {
        Clock clock = RoutinelyClocks.micros(Clock.fixed(Instant.parse("2026-11-13T05:58:44.740903532Z"), SEOUL));

        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-11-13T05:58:44.740903Z"));
        assertThat(LocalDateTime.now(clock)).isEqualTo(LocalDateTime.parse("2026-11-13T14:58:44.740903"));
    }

    @Test
    @DisplayName("반올림 경계(…999999500)에서도 다음 초로 넘어가지 않는다")
    void micros_roundingBoundary_staysInSameSecond() {
        Clock clock = RoutinelyClocks.micros(Clock.fixed(Instant.parse("2026-11-13T14:59:59.999999500Z"), ZoneOffset.UTC));

        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-11-13T14:59:59.999999Z"));
    }

    @Test
    @DisplayName("millis()는 예외 없이 base의 밀리초를 돌려준다 — Clock.tick(1µs)는 여기서 0으로 나눈다")
    void millis_returnsBaseMillis() {
        Clock base = Clock.fixed(Instant.parse("2026-11-13T05:58:44.740903532Z"), SEOUL);

        assertThat(RoutinelyClocks.micros(base).millis()).isEqualTo(base.millis());
    }

    @Test
    @DisplayName("시간대는 base를 따르고, withZone 뒤에도 마이크로초 절단이 유지된다")
    void withZone_keepsTruncation() {
        Clock clock = RoutinelyClocks.micros(Clock.fixed(Instant.parse("2026-11-13T05:58:44.740903532Z"), SEOUL));

        Clock utc = clock.withZone(ZoneOffset.UTC);

        assertThat(clock.getZone()).isEqualTo(SEOUL);
        assertThat(utc.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(utc.instant()).isEqualTo(Instant.parse("2026-11-13T05:58:44.740903Z"));
    }

    @Test
    @DisplayName("운영 시스템 시계도 마이크로초 아래가 0이고 시간대를 지킨다")
    void system_isMicrosInGivenZone() {
        Clock clock = RoutinelyClocks.system(SEOUL);

        assertThat(clock.getZone()).isEqualTo(SEOUL);
        assertThat(clock.instant().getNano() % 1_000).isZero();
        assertThat(RoutinelyClocks.systemDefaultZone().getZone()).isEqualTo(ZoneId.systemDefault());
    }

    @Test
    @DisplayName("이미 마이크로초 Clock이면 다시 감싸지 않는다")
    void micros_alreadyMicros_returnsSame() {
        Clock clock = RoutinelyClocks.micros(Clock.systemUTC());

        assertThat(RoutinelyClocks.micros(clock)).isSameAs(clock);
    }
}
