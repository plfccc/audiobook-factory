package com.audiobookfactory.control.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

public class AccessTokenFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String WORKER_API_PREFIX = "/api/v1/workers/";
    private static final String ASSET_API_PREFIX = "/api/v1/assets/";
    private final AppProperties appProperties;

    public AccessTokenFilter(AppProperties appProperties) {
        this.appProperties = Objects.requireNonNull(appProperties, "appProperties");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String configuredToken = appProperties.accessToken();
        if (!StringUtils.hasText(configuredToken)) {
            // 未配置应用令牌时，依赖部署入口提供访问保护。
            filterChain.doFilter(request, response);
            return;
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            reject(response);
            return;
        }

        String suppliedToken = authorization.substring(BEARER_PREFIX.length());
        if (!StringUtils.hasText(suppliedToken)
                || !MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            reject(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank()
                && requestUri.startsWith(contextPath)) {
            requestUri = requestUri.substring(contextPath.length());
        }
        return requestUri.startsWith(WORKER_API_PREFIX)
                || requestUri.startsWith(ASSET_API_PREFIX);
    }

    private void reject(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }
}
