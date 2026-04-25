package com.routinely.user_service.infrastructure.redis;

import com.routinely.user_service.infrastructure.config.JwtProperties;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "rt:";
    private static final RedisScript<String> CONSUME_SCRIPT = RedisScript.of("""
                local value = redis.call('GET', KEYS[1])
                if not value then
                    return nil
                end
                redis.call('DEL', KEYS[1])
                return value
                """, String.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public RefreshTokenStore(StringRedisTemplate redisTemplate, JwtProperties properties) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofMillis(properties.refreshExpirationMs());
    }

    public String save(Long userId) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(KEY_PREFIX + token, userId.toString(), ttl);
        return token;
    }

    public Long consumeUserId(String token) {
        String value = redisTemplate.execute(CONSUME_SCRIPT, List.of(KEY_PREFIX + token));
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void delete(String token) {
        redisTemplate.delete(KEY_PREFIX + token);
    }
}
