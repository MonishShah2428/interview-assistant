package com.interviewprep.web;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Enables cross-origin REST and SSE calls when the frontend is served from a separate origin (e.g.
 * Firebase Hosting). Disabled by default when {@code app.cors.allowed-origins} is blank.
 */
@Configuration
class CorsConfig implements WebMvcConfigurer {

  private final String[] allowedOrigins;

  CorsConfig(@Value("${app.cors.allowed-origins:}") String rawAllowedOrigins) {
    this.allowedOrigins =
        Arrays.stream(rawAllowedOrigins.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toArray(String[]::new);
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    if (allowedOrigins.length == 0) {
      return;
    }
    for (String pattern : new String[] {"/tracks", "/tracks/**"}) {
      registry
          .addMapping(pattern)
          .allowedOrigins(allowedOrigins)
          .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
          .allowedHeaders("*")
          .maxAge(3600);
    }
  }
}
