package com.routinely.jpa.config;

import com.routinely.core.time.RoutinelyClocks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * {@code createdAt} · {@code updatedAt}을 서비스의 Clock에서 마이크로초 정밀도로 만든다(#213).
 *
 * <ul>
 *   <li><b>시각</b>은 서비스의 {@link Clock} 빈에서 읽는다. 빈이 없으면(chat · notification) 시스템 시계를
 *       {@link RoutinelyClocks#micros(Clock)}로 감싸 쓴다. 여기서 Clock 빈을 새로 등록하지 않는다 —
 *       테스트가 이 설정을 {@code @Import}한 뒤 자기 {@code clock} 빈을 두면 이름이 겹친다.</li>
 *   <li><b>시간대</b>는 JVM 기본을 그대로 쓴다. #213 이전의 {@code LocalDateTime.now()}와 같은 벽시계다.
 *       서비스 Clock의 시간대(예: Asia/Seoul)로 바꾸면 JVM 시간대가 다른 환경에서 저장 값이 달라진다 — 시간대 정리는 별도 작업이다.</li>
 * </ul>
 */
@AutoConfiguration
@EnableJpaAuditing(dateTimeProviderRef = JpaAuditingConfig.DATE_TIME_PROVIDER)
public class JpaAuditingConfig {

    public static final String DATE_TIME_PROVIDER = "routinelyAuditingDateTimeProvider";

    @Bean(DATE_TIME_PROVIDER)
    public DateTimeProvider routinelyAuditingDateTimeProvider(ObjectProvider<Clock> clockProvider) {
        return () -> {
            Clock clock = RoutinelyClocks.micros(
                    clockProvider.getIfAvailable(Clock::systemDefaultZone));
            return Optional.of(LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault()));
        };
    }
}
