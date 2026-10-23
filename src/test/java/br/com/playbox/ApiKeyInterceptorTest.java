package br.com.playbox;

import br.com.playbox.config.ApiKeyInterceptor;
import br.com.playbox.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyInterceptorTest {

    private ApiKeyInterceptor interceptor;
    private AppProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.setApiKey("test-secret-key-123");
        interceptor = new ApiKeyInterceptor(properties, new ObjectMapper());
    }

    @Test
    void testPreHandleWithoutKeyReturns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/trigger/monitor");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("UNAUTHORIZED"));
    }

    @Test
    void testPreHandleWithInvalidKeyReturns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/trigger/monitor");
        request.addHeader("X-API-KEY", "wrong-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("UNAUTHORIZED"));
    }

    @Test
    void testPreHandleWithValidKeyReturnsTrue() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/trigger/monitor");
        request.addHeader("X-API-KEY", "test-secret-key-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        assertEquals(200, response.getStatus());
    }

    @Test
    void testPreHandleOptionsAllowedWithoutKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/trigger/monitor");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
    }

    @Test
    void testLoginPathBypassesKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
    }

    @Test
    void testPreHandleWithBearerToken() throws Exception {
        br.com.playbox.service.AuthService authService = new br.com.playbox.service.AuthService(properties);
        String token = authService.login("admin", "PlayBox@2026!");
        assertNotNull(token);

        ApiKeyInterceptor authInterceptor = new ApiKeyInterceptor(properties, new ObjectMapper(), authService);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/maquinas");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = authInterceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
    }
}

