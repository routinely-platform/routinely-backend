package com.routinely.routine_service.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.TestPropertySource;

/** 운영 스키마(Flyway)와 pgJDBC 바인딩 경로 그대로 확인한다. */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17-alpine:///server_time_precision_db",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@DisplayName("서버 시각 정밀도 — PostgreSQL")
class ServerTimePrecisionPostgresTest extends ServerTimePrecisionPersistenceTest {
}
