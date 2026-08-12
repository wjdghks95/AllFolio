package com.allfolio.config;

import com.allfolio.infra.security.JwtFilter;
import com.allfolio.infra.security.JwtIssuer;
import com.allfolio.infra.security.JwtProperties;
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

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * entryPoint를 필드가 아닌 메서드 파라미터로 받는다.
     * RestAuthenticationEntryPoint가 handlerExceptionResolver에 의존하므로 필드 주입 시 순환이 생긴다.
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtIssuer jwtIssuer,
                                    AuthenticationEntryPoint entryPoint) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Task 019에서 /v1/auth/logout 등이 추가돼도 조용히 열리지 않도록 와일드카드 대신 명시 나열한다.
                        .requestMatchers("/v1/auth/signup", "/v1/auth/login").permitAll()
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
