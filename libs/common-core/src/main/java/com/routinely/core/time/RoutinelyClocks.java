package com.routinely.core.time;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 서버 시각의 유일한 출처. 운영 Clock 빈 · Auditing 기본값 · 테스트가 모두 이 팩토리를 거친다.
 *
 * <p>DB 시각 컬럼(PostgreSQL {@code TIMESTAMPTZ}, H2 {@code TIMESTAMP(6)})은 마이크로초까지만 담는다.
 * JVM 시계(Linux)는 나노초까지 주므로 그대로 쓰면 저장 직후 메모리 값과 DB 재조회 값이 달라진다(#213).
 * 만드는 순간 마이크로초 아래를 버려 두면 드라이버 · DB가 반올림할 자리가 남지 않는다.
 *
 * <p>{@code Clock.tick(base, Duration.ofNanos(1_000))}을 쓰지 않는다 — JDK의 {@code TickClock.millis()}가
 * 틱 길이를 밀리초로 나눠(=0) {@code ArithmeticException}을 던진다.
 */
public final class RoutinelyClocks {

    private RoutinelyClocks() {
    }

    /** 운영 시스템 시계(마이크로초). 서비스 Clock 빈은 이걸로 만든다. */
    public static Clock system(ZoneId zone) {
        return micros(Clock.system(zone));
    }

    /** JVM 기본 시간대의 시스템 시계(마이크로초). */
    public static Clock systemDefaultZone() {
        return micros(Clock.systemDefaultZone());
    }

    /** {@code base}의 시각을 마이크로초 아래에서 버리는 Clock. 시간대는 {@code base}를 따른다. 테스트는 나노초 원천을 여기에 넣는다. */
    public static Clock micros(Clock base) {
        Objects.requireNonNull(base, "base");
        if (base instanceof MicrosClock) {
            return base;
        }
        return new MicrosClock(base);
    }
}
