package com.interviewprep.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Forwards browser HTML navigations for SPA routes under {@code /tracks/...} to {@code
 * /index.html}, mirroring Vite's dev proxy bypass so JSON/SSE clients keep hitting Spring MVC
 * endpoints on the same paths.
 */
@Component
class SpaRoutingFilter extends OncePerRequestFilter {

  private static final Pattern SPA_TRACK_ROUTE =
      Pattern.compile("^/tracks/[^/]+(?:/topics/[^/]+)?$");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (isHtmlSpaNavigation(request)) {
      request.getRequestDispatcher("/index.html").forward(request, response);
      return;
    }
    filterChain.doFilter(request, response);
  }

  private static boolean isHtmlSpaNavigation(HttpServletRequest request) {
    if (!"GET".equalsIgnoreCase(request.getMethod())) {
      return false;
    }
    String accept = request.getHeader(HttpHeaders.ACCEPT);
    if (accept == null || !accept.contains(MediaType.TEXT_HTML_VALUE)) {
      return false;
    }
    String path = request.getRequestURI();
    return path != null && SPA_TRACK_ROUTE.matcher(path).matches();
  }
}
