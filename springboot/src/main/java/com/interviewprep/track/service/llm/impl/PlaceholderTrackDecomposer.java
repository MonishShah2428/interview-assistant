package com.interviewprep.track.service.llm.impl;

import com.interviewprep.track.service.llm.DecomposedTopic;
import com.interviewprep.track.service.llm.TrackDecomposer;
import com.interviewprep.track.service.llm.TrackDecompositionException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Placeholder until the real decomposer is built. Always fails non-retryably — a silently-empty
 * result would look like a real (if empty) track; failing loudly is the honest state.
 */
@Component
class PlaceholderTrackDecomposer implements TrackDecomposer {

  @Override
  public List<DecomposedTopic> decompose(String goal, String level) {
    throw new TrackDecompositionException("TrackDecomposer is not implemented yet");
  }
}
