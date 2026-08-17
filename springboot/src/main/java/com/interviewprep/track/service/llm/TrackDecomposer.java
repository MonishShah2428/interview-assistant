package com.interviewprep.track.service.llm;

import java.util.List;

/**
 * Turns a goal (or, later, a JD) plus a track level into root topic labels. Deliberately not "2
 * levels deep" yet — {@link com.interviewprep.track.service.TrackService} only creates roots;
 * getting to the second level is {@code ExpansionService.expandTopic}, not this call.
 *
 * <p>TODO: implement as an {@code @AiService}, same style as {@code TopicExpander}. Until then
 * {@link com.interviewprep.track.service.llm.impl.PlaceholderTrackDecomposer} is the only bean and
 * always throws {@link TrackDecompositionException}.
 */
public interface TrackDecomposer {

  List<DecomposedTopic> decompose(String goal, String level);
}
