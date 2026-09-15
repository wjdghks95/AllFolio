package com.allfolio.config;

import com.allfolio.infra.security.JwtFilter;
import com.allfolio.infra.security.JwtIssuer;
import com.allfolio.infra.security.JwtProperties;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Capacitor(Task 030) 패키징 앱은 Vite dev 서버 프록시를 거치지 않고 이 커스텀 origin에서 직접
     * API를 호출한다 — iOS는 capacitor://localhost, Android는 https://localhost(포트 없음)가 WebView
     * 기본 스킴이다(capacitor.config.ts가 androidScheme을 커스터마이즈하지 않았으므로 Capacitor
     * 기본값 그대로 androidScheme=https 적용 — secure context가 필요한 Web API 때문에 Capacitor가
     * http 대신 https를 기본값으로 권장). 두 origin만 명시 나열한다 — 이 API는 Authorization 헤더로
     * 인증하는 신뢰된 클라이언트만 호출하므로 origin을 넓히지 않는다.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("capacitor://localhost", "https://localhost"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Last-Event-ID"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * entryPoint를 필드가 아닌 메서드 파라미터로 받는다.
     * RestAuthenticationEntryPoint가 handlerExceptionResolver에 의존하므로 필드 주입 시 순환이 생긴다.
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtIssuer jwtIssuer,
                                    AuthenticationEntryPoint entryPoint,
                                    CorsConfigurationSource corsConfigurationSource) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // SSE(Task 028)는 emitter 완료 시 서블릿 컨테이너가 ASYNC로 필터 체인을 재디스패치한다.
                        // JwtFilter(OncePerRequestFilter)는 기본적으로 ASYNC 재디스패치에서 재실행되지 않아
                        // (docs/ROADMAP.md 657~659행 실측 — MdcFilter와 동일한 배경) 그 시점엔 SecurityContext가
                        // 비어 있는데, AuthorizationFilter는 기본값(shouldFilterAllDispatcherTypes=true)으로
                        // ASYNC에도 인가를 적용해 매번 AuthorizationDeniedException을 던진다(원 요청은 이미
                        // 정상 인증을 통과했으므로 재디스패치 시점의 재검사는 불필요 — Task 028 code-reviewer M3
                        // 실측). ASYNC 디스패치는 인가 대상에서 제외한다.
                        // 주의: 이 permitAll은 SSE 경로 전용이 아니라 전역 ASYNC 디스패치에 적용된다 —
                        // 향후 다른 비동기 엔드포인트를 추가할 때 이 사실을 재검토할 것(Task 028 code-reviewer m8).
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        // 와일드카드 대신 명시 나열한다. refresh/logout이 permitAll이어도 안전한 이유:
                        // 인증 주체는 URL 접근 권한이 아니라 요청 본문의 Refresh Token 자체다.
                        .requestMatchers("/v1/auth/signup", "/v1/auth/login",
                                "/v1/auth/refresh", "/v1/auth/logout").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/info", "/actuator/prometheus").permitAll()
                        // 보안 필터는 ERROR 디스패치에도 적용된다 — 열어두지 않으면 404/500이 401로 뒤바뀐다.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint))
                .addFilterBefore(new JwtFilter(jwtIssuer), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
