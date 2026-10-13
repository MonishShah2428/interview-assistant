package com.interviewprep.node.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.interviewprep.node.entity.ConceptOrigin;
import com.interviewprep.node.entity.EnrichmentStatus;
import com.interviewprep.node.entity.ExpansionStatus;
import com.interviewprep.node.entity.ResourceLane;
import com.interviewprep.node.entity.ResourceType;
import com.interviewprep.node.service.ExpansionService;
import com.interviewprep.node.service.TopicQueryService;
import com.interviewprep.node.service.dto.ConceptView;
import com.interviewprep.node.service.dto.ResourceDetailView;
import com.interviewprep.node.service.dto.TopicDetailView;
import com.interviewprep.node.sse.EnrichmentEventPublisher;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NodeController.class)
class NodeControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ExpansionService expansionService;
  @MockitoBean private TopicQueryService topicQueryService;
  @MockitoBean private EnrichmentEventPublisher enrichmentEventPublisher;

  @Test
  void topicDetailReturnsResourcesAndConcepts() throws Exception {
    when(topicQueryService.getTopic(1L, 2L))
        .thenReturn(
            new TopicDetailView(
                2L,
                "Caching",
                true,
                false,
                ExpansionStatus.not_expanded,
                EnrichmentStatus.ready,
                null,
                List.of(
                    new ResourceDetailView(
                        10L,
                        "Caching Overview",
                        "https://aws.amazon.com/caching/",
                        null,
                        ResourceType.docs,
                        ResourceLane.read,
                        "caching explained",
                        0)),
                List.of(new ConceptView(20L, "Cache Aside Pattern", ConceptOrigin.upfront))));

    mockMvc
        .perform(get("/tracks/1/topics/2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enrichmentStatus").value("ready"))
        .andExpect(jsonPath("$.resources[0].lane").value("read"))
        .andExpect(jsonPath("$.concepts[0].origin").value("upfront"));
  }

  @Test
  void topicOutsideTrackReturns404() throws Exception {
    when(topicQueryService.getTopic(1L, 99L)).thenThrow(new NoSuchElementException("nope"));

    mockMvc.perform(get("/tracks/1/topics/99")).andExpect(status().isNotFound());
  }
}
