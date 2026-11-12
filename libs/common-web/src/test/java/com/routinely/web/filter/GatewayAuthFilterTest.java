package com.routinely.web.filter;

import com.routinely.core.constant.HeaderConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("GatewayAuthFilter")
class GatewayAuthFilterTest {

    private static final String SECRET = "test-secret";

    GatewayAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new GatewayAuthFilter(SECRET, new ObjectMapper());
    }

    @Test
    @DisplayName("doFilter_올바른시크릿_체인진행")
    void doFilter_withValidSecret_proceedsChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HeaderConstants.GATEWAY_SECRET, SECRET);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("doFilter_잘못된시크릿_401응답_체인차단")
    void doFilter_withInvalidSecret_respondsUnauthorized() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HeaderConstants.GATEWAY_SECRET, "wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
    }

    @Test
    @DisplayName("doFilter_시크릿헤더없음_401응답")
    void doFilter_withoutSecretHeader_respondsUnauthorized() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/actuator", "/actuator/prometheus", "/actuator/health/liveness"})
    @DisplayName("shouldNotFilter_actuator경로_검사하지않음")
    void shouldNotFilter_actuatorPath_skipsCheck(String path) {
        assertThat(filter.shouldNotFilter(requestTo(path))).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/api/v1/routines",
            "/actuatorx",                       // 접두사만 같은 다른 경로
            "/actuator-admin/secret",
            "/actuator/../api/v1/routines",     // 정규화하면 /api/v1/routines
            "/api/actuator/prometheus"          // actuator가 앞이 아니다
    })
    @DisplayName("shouldNotFilter_actuator가아닌경로_검사함")
    void shouldNotFilter_nonActuatorPath_checks(String path) {
        assertThat(filter.shouldNotFilter(requestTo(path))).isFalse();
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"", " "})
    @DisplayName("생성_빈시크릿_예외")
    void construct_blankSecret_throws(String blank) {
        assertThatThrownBy(() -> new GatewayAuthFilter(blank, new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gateway.secret");
    }

    private static MockHttpServletRequest requestTo(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        // DispatcherServlet이 "/"에 매핑되면 컨테이너는 경로 전체를 servletPath로 준다.
        request.setServletPath(path);
        return request;
    }
}
