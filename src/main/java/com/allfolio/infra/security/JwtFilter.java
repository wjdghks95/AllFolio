package com.allfolio.infra.security;

import com.allfolio.infra.logging.MdcKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Bearer 토큰을 검증해 SecurityContext에 인증을 채운다.
 *
 * <p>@Component를 붙이지 않는다 — 서블릿 컨테이너가 일반 필터로 자동 등록해
 * 시큐리티 체인과 합쳐 2회 실행되기 때문이다. SecurityConfig에서만 인스턴스화한다.
 *
 * <p>여기서는 예외를 던지지 않는다. 검증 실패는 SecurityContext를 비워둔 채 통과시키고,
 * 최종 401 판단은 authorizeHttpRequests + AuthenticationEntryPoint가 담당한다.
 */
public class JwtFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtIssuer jwtIssuer;

    public JwtFilter(JwtIssuer jwtIssuer) {
        this.jwtIssuer = jwtIssuer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            jwtIssuer.resolveUserId(token).ifPresent(userId -> authenticate(userId, request));
        }

        chain.doFilter(request, response);
    }

    private void authenticate(UUID userId, HttpServletRequest request) {
        var authentication = new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        // MdcFilter가 체인 전체를 감싸며 요청 종료 시 MDC.clear()하므로 여기서 정리할 필요는 없다.
        MDC.put(MdcKeys.USER_ID, userId.toString());
    }
}
