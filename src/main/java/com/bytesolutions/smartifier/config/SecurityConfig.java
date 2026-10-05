package com.bytesolutions.smartifier.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(access -> access
                .requestMatchers("/", "/login", "/register", "/app/**", "/favicon.ico", "/error",
                        "/api/auth/csrf", "/api/auth/register", "/api/auth/login").permitAll()
                .requestMatchers("/admin/**", "/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/member/**", "/api/member/**").hasRole("MEMBER")
                .anyRequest().authenticated())
            .formLogin(login -> login.loginPage("/login").loginProcessingUrl("/api/auth/login")
                .usernameParameter("email")
                .successHandler((request, response, authentication) -> response.setStatus(204))
                .failureHandler((request, response, exception) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"message\":\"Invalid email or password.\"}");
                }))
            .logout(logout -> logout.logoutUrl("/api/auth/logout").invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
            .requestCache(cache -> cache.disable())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> {
                    if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
                        response.setStatus(401);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"message\":\"Please sign in.\"}");
                    } else {
                        response.sendRedirect(request.getContextPath() + "/login");
                    }
                })
                .accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"message\":\"Access denied. Refresh the page and try again.\"}");
                }));
        // Keep default session fixation and CSRF protections. Angular fetches a masked token
        // from /api/auth/csrf and sends it as a header on every mutation.
        return http.build();
    }
}
