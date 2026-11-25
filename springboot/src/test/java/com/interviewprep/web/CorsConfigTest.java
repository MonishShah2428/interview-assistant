package com.interviewprep.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = CorsConfigTest.StubTracksController.class)
@Import({CorsConfig.class, CorsConfigTest.StubTracksController.class})
@TestPropertySource(
    properties =
        "app.cors.allowed-origins=https://interview-assistant-monishs.web.app,"
            + "https://interview-assistant-monishs.firebaseapp.com")
class CorsConfigTest {

  private static final String ALLOWED_ORIGIN = "https://interview-assistant-monishs.web.app";

  @Autowired private MockMvc mockMvc;

  @Test
  void allowsPreflightAndApiAndSseFromConfiguredOrigin() throws Exception {
    mockMvc
        .perform(
            options("/tracks")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));

    mockMvc
        .perform(get("/tracks").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));

    mockMvc
        .perform(
            get("/tracks/1/events")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
  }

  @Test
  void rejectsDisallowedOrigin() throws Exception {
    mockMvc
        .perform(get("/tracks").header(HttpHeaders.ORIGIN, "https://evil.example.com"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
  }

  @RestController
  @RequestMapping("/tracks")
  static class StubTracksController {

    @GetMapping
    String list() {
      return "[]";
    }

    @PostMapping
    String create() {
      return "{}";
    }

    @GetMapping(path = "/{trackId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    String events(@PathVariable Long trackId) {
      return ":connected\n\n";
    }
  }
}
