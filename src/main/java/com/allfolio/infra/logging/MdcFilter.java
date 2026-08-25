package com.allfolio.infra.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 요청마다 traceId를 발급해 MDC에 채운다. userId는 인증이 끝나야 알 수 있으므로
 * 이 필터가 아니라 JwtFilter.authenticate()에서 직접 MDC에 채운다.
 *
 * <p>@Order(HIGHEST_PRECEDENCE)로 Spring Security 필터 체인(JwtFilter 포함)보다도 먼저 실행되게
 * 한다. 순서를 프레임워크 기본값에 맡기면(단순 @Component 등록은 FilterRegistrationBean의
 * 기본 순서인 LOWEST_PRECEDENCE로 등록되는데, Security 체인은 SecurityProperties.DEFAULT_FILTER_ORDER
 * = -100으로 훨씬 이르게 등록돼) 이 필터가 시큐리티 체인 뒤로 밀려나, 체인 내부(JwtFilter의 JWT
 * 파싱 실패 진단 로그 등)에는 traceId가 아예 안 붙는 문제가 실측으로 확인됐다(code-reviewer 지적,
 * Task 014). 그래서 순서를 프레임워크 기본값에 맡기지 않고 명시적으로 최우선으로 선언한다.
 *
 * <p>이 필터는 시큐리티 체인에 별도 등록되지 않고 서블릿 컨테이너에 @Component로 한 번만
 * 등록되므로 이중 실행 경로가 없다.
 *
 * <p>Virtual Thread 환경(spring.threads.virtual.enabled=true)에서 MDC는 스레드 로컬 기반이므로
 * finally에서 반드시 MDC.clear()로 정리해, 스레드가 재사용될 때 이전 요청의 값이 새어 들어가지
 * 않게 한다. 이 필터가 체인 전체를 감싸므로, 안쪽의 JwtFilter가 채운 userId도 여기서 함께 정리된다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            MDC.put(MdcKeys.TRACE_ID, UUID.randomUUID().toString());
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
