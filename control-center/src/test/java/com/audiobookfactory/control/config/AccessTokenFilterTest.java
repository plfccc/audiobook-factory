package com.audiobookfactory.control.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AccessTokenFilterTest {

    @Test
    void allowsRequestWithTheConfiguredBearerToken() throws ServletException, IOException {
        String configuredToken = UUID.randomUUID().toString();
        AppProperties appProperties = appPropertiesWithAccessToken(configuredToken);
        AccessTokenFilter filter = new AccessTokenFilter(appProperties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + configuredToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, recordingChain(chainCalled));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chainCalled).isTrue();
    }

    @Test
    void rejectsRequestWithTheWrongBearerToken() throws ServletException, IOException {
        AccessTokenFilter filter = new AccessTokenFilter(appPropertiesWithAccessToken(UUID.randomUUID().toString()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + UUID.randomUUID());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, recordingChain(chainCalled));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(chainCalled).isFalse();
    }

    @Test
    void rejectsRequestWithoutAnAuthorizationHeader() throws ServletException, IOException {
        AccessTokenFilter filter = new AccessTokenFilter(appPropertiesWithAccessToken(UUID.randomUUID().toString()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, recordingChain(chainCalled));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chainCalled).isFalse();
    }

    @Test
    void allowsRequestWhenTheAccessTokenConfigurationIsBlank() throws ServletException, IOException {
        AppProperties appProperties = new AppProperties();
        appProperties.setAccessToken("  ");
        AccessTokenFilter filter = new AccessTokenFilter(appProperties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, recordingChain(chainCalled));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chainCalled).isTrue();
    }

    private AppProperties appPropertiesWithAccessToken(String token) {
        AppProperties appProperties = new AppProperties();
        appProperties.setAccessToken(token);
        return appProperties;
    }

    private FilterChain recordingChain(AtomicBoolean chainCalled) {
        return (request, response) -> chainCalled.set(true);
    }
}
