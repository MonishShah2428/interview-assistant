package com.interviewprep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SpaRoutingFilterTest {

  private final SpaRoutingFilter filter = new SpaRoutingFilter();

  @Test
  void forwardsBrowserHtmlRequestsForSpaRoutesToIndexHtml() throws Exception {
    for (String path : new String[] {"/tracks/1", "/tracks/42/topics/99"}) {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
      request.addHeader(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml");
      MockHttpServletResponse response = new MockHttpServletResponse();
      FilterChain chain = mock(FilterChain.class);

      filter.doFilter(request, response, chain);

      assertThat(response.getForwardedUrl()).isEqualTo("/index.html");
      verifyNoInteractions(chain);
    }
  }

  @Test
  void passesThroughApiSseAndPostRequests() throws Exception {
    assertPassesThrough("GET", "/tracks/1", "application/json");
    assertPassesThrough("GET", "/tracks/1", "*/*");
    assertPassesThrough("GET", "/tracks/1/events", "text/html");
    assertPassesThrough("GET", "/tracks/1/events", "text/event-stream");
    assertPassesThrough("POST", "/tracks/1/topics/2/expand", "text/html");
  }

  private void assertPassesThrough(String method, String path, String accept) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.addHeader(HttpHeaders.ACCEPT, accept);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getForwardedUrl()).isNull();
    verify(chain).doFilter(request, response);
  }
}
