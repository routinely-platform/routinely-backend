package com.routinely.core.time;

import java.time.Clock;

/** 팩토리와 같은 패키지라도 RoutinelyClocks · MicrosClock이 아니면 규칙 ②에 걸려야 한다(ServerTimeRulesTest 픽스처). */
@SuppressWarnings("unused")
class SamePackageViolation {
    Clock clock() {
        return Clock.systemUTC();
    }
}
