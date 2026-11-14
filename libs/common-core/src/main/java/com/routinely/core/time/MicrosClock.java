package com.routinely.core.time;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** {@link RoutinelyClocks#micros(Clock)}가 만드는 마이크로초 Clock. 직접 생성하지 않는다. */
final class MicrosClock extends Clock {

    private final Clock base;

    MicrosClock(Clock base) {
        this.base = base;
    }

    @Override
    public ZoneId getZone() {
        return base.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        if (zone.equals(base.getZone())) {
            return this;
        }
        return new MicrosClock(base.withZone(zone));
    }

    /** 마이크로초 아래를 버린다(floor). 반올림하지 않는다 — 반올림하면 다음 초 · 다음 날로 넘어갈 수 있다. */
    @Override
    public Instant instant() {
        return base.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** 밀리초는 마이크로초 절단의 영향을 받지 않으므로 base 값을 그대로 쓴다. */
    @Override
    public long millis() {
        return base.millis();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof MicrosClock other && base.equals(other.base);
    }

    @Override
    public int hashCode() {
        return base.hashCode() ^ 0x4D494352;
    }

    @Override
    public String toString() {
        return "MicrosClock[" + base + "]";
    }
}
