package com.routinely.gateway_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class GatewayServiceApplicationTests {

	@MockitoBean
	ReactiveStringRedisTemplate redisTemplate;

	@Test
	void contextLoads() {
	}

}
