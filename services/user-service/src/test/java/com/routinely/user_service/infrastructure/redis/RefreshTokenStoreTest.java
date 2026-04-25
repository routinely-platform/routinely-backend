package com.routinely.user_service.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.routinely.user_service.infrastructure.config.JwtProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@DisplayName("RefreshTokenStore")
class RefreshTokenStoreTest {

    @Test
    @DisplayName("토큰소비는_LuaScript로_원자적으로_처리한다")
    void consumeUserId_executesLuaScriptAndReturnsUserId() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RefreshTokenStore refreshTokenStore = new RefreshTokenStore(
                redisTemplate,
                new JwtProperties("secret", 900_000L, 604_800_000L, false)
        );

        when(redisTemplate.execute(org.mockito.ArgumentMatchers.<RedisScript<String>>any(),
                eq(List.of("rt:refresh-token")), any(Object[].class)))
                .thenReturn("1");

        Long userId = refreshTokenStore.consumeUserId("refresh-token");

        assertThat(userId).isEqualTo(1L);
        verify(redisTemplate).execute(org.mockito.ArgumentMatchers.<RedisScript<String>>any(),
                eq(List.of("rt:refresh-token")), any(Object[].class));
    }

    @Test
    @DisplayName("토큰이없으면_null을_반환한다")
    void consumeUserId_whenTokenDoesNotExist_returnsNull() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RefreshTokenStore refreshTokenStore = new RefreshTokenStore(
                redisTemplate,
                new JwtProperties("secret", 900_000L, 604_800_000L, false)
        );

        when(redisTemplate.execute(org.mockito.ArgumentMatchers.<RedisScript<String>>any(),
                eq(List.of("rt:missing-token")), any(Object[].class)))
                .thenReturn(null);

        assertThat(refreshTokenStore.consumeUserId("missing-token")).isNull();
    }
}
