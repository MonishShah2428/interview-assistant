package com.interviewprep.track.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.interviewprep.track.service.TrackService;
import com.interviewprep.track.service.dto.TrackView;
import com.interviewprep.track.service.llm.TrackDecompositionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TrackController.class)
class TrackControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private TrackService trackService;

  @Test
  void blankGoalReturns400() throws Exception {
    mockMvc
        .perform(
            post("/tracks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goal\": \"\", \"level\": \"mid\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void oversizedGoalReturns400() throws Exception {
    String tooLong = "x".repeat(501);
    mockMvc
        .perform(
            post("/tracks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goal\": \"" + tooLong + "\", \"level\": \"mid\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void decomposerFailureReturns502() throws Exception {
    when(trackService.createTrack(anyString(), anyString()))
        .thenThrow(new TrackDecompositionException("boom"));

    mockMvc
        .perform(
            post("/tracks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goal\": \"prep me for an SDE II role\", \"level\": \"mid\"}"))
        .andExpect(status().isBadGateway());
  }

  @Test
  void validRequestReturns201() throws Exception {
    when(trackService.createTrack(anyString(), anyString()))
        .thenReturn(new TrackView(1L, "prep me for an SDE II role", "mid", java.util.List.of()));

    mockMvc
        .perform(
            post("/tracks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goal\": \"prep me for an SDE II role\", \"level\": \"mid\"}"))
        .andExpect(status().isCreated());
  }
}
