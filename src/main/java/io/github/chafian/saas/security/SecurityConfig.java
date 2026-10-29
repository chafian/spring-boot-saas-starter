package io.github.chafian.saas.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  @Bean
  JwtDecoder jwtDecoder(
      @Value("${app.security.issuer}") String issuer,
      @Value("${app.security.jwk-set-uri}") String jwks,
      @Value("${app.security.audience}") String audience) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            List.of(JwtValidators.createDefaultWithIssuer(issuer), requiredClaims(audience))));
    return decoder;
  }

  private OAuth2TokenValidator<Jwt> requiredClaims(String audience) {
    return jwt -> {
      String subject = jwt.getSubject();
      boolean valid =
          subject != null
              && !subject.isBlank()
              && subject.length() <= 200
              && jwt.getExpiresAt() != null
              && jwt.getAudience() != null
              && jwt.getAudience().contains(audience);
      return valid
          ? OAuth2TokenValidatorResult.success()
          : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    };
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    // Only Authorization bearer tokens are accepted; no cookie-based authentication exists.
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness")
                    .permitAll()
                    .requestMatchers("/api/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .jwt(jwt -> {})
                    .authenticationEntryPoint(
                        (request, response, exception) -> {
                          response.setHeader("WWW-Authenticate", "Bearer");
                          response.setStatus(401);
                          response.setContentType("application/problem+json");
                          response.getWriter().write("{\"title\":\"Unauthorized\",\"status\":401}");
                        }))
        .headers(
            headers ->
                headers.contentSecurityPolicy(
                    csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")));
    return http.build();
  }
}
