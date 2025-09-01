package com.app.swingtrader.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // This rule requires authentication for ANY request to the application
                        .anyRequest().authenticated()
                )
                // This enables the HTTP Basic authentication pop-up
                .httpBasic(withDefaults())
                // This also enables a default, form-based login page if you visit the URL directly
                .formLogin(withDefaults());

        return http.build();
    }
}
