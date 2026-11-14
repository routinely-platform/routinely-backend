package com.routinely.challenge_service.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.TestPropertySource;

/** H2는 {@code jsonb}를 모르므로 같은 이름의 도메인을 만들어 Hibernate DDL이 Outbox · Inbox 테이블을 만들게 한다. */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:server_time_precision;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;"
                + "INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS VARCHAR",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@DisplayName("서버 시각 정밀도 — H2")
class ServerTimePrecisionH2Test extends ServerTimePrecisionPersistenceTest {
}
