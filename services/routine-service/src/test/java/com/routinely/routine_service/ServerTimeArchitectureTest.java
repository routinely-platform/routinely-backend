package com.routinely.routine_service;

import com.routinely.core.time.ServerTimeRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("서버 시각 정적 검사 (#213)")
class ServerTimeArchitectureTest {

    @Test
    @DisplayName("운영 코드는 주입된 Clock에서만 시각을 만들고, Clock은 RoutinelyClocks에서만 만든다")
    void serverTime_comesOnlyFromClockFactory() {
        ServerTimeRules.check("com.routinely");
    }
}
