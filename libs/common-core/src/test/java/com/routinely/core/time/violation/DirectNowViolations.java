package com.routinely.core.time.violation;

import java.time.Clock;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.function.Supplier;

/** ServerTimeRules가 잡아야 하는 경우와 허용해야 하는 경우를 모은 픽스처. 운영 코드가 아니다. */
@SuppressWarnings("unused")
public class DirectNowViolations {

    public static class NoArgNow {
        LocalDateTime now() { return LocalDateTime.now(); }
    }

    public static class ZoneNow {
        LocalDateTime now() { return LocalDateTime.now(ZoneId.of("UTC")); }
    }

    public static class InstantNow {
        Instant now() { return Instant.now(); }
    }

    public static class SystemUtcClock {
        LocalDateTime now() { return LocalDateTime.now(Clock.systemUTC()); }
    }

    public static class SystemClockBean {
        Clock clock() { return Clock.system(ZoneId.of("Asia/Seoul")); }
    }

    public static class MethodReference {
        Supplier<Clock> clock() { return Clock::systemUTC; }
    }

    public static class InstantSourceSystem {
        Instant now() { return InstantSource.system().instant(); }
    }

    /** 허용 — 주입된 Clock과 날짜 */
    public static class Allowed {
        LocalDateTime now(Clock clock) { return LocalDateTime.now(clock); }
        LocalDate today() { return LocalDate.now(); }
    }
}
