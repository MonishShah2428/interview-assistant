package com.interviewprep.node.sse;

import com.interviewprep.node.entity.EnrichmentJobKind;
import com.interviewprep.node.entity.EnrichmentStatus;

/**
 * Pushed to a track's subscribers when one of its topics finishes an enrichment job of either kind.
 * For {@link EnrichmentJobKind#resource_refresh}, {@code status} reports how the refresh went
 * ({@code ready} or {@code failed}), not the topic's own {@code enrichment_status}, which a refresh
 * never changes; {@code newResourceCount} is set only on a successful refresh.
 */
public record EnrichmentEvent(
    Long topicId, EnrichmentJobKind kind, EnrichmentStatus status, Integer newResourceCount) {

  public static EnrichmentEvent initial(Long topicId, EnrichmentStatus status) {
    return new EnrichmentEvent(topicId, EnrichmentJobKind.initial, status, null);
  }

  public static EnrichmentEvent refreshed(Long topicId, int newResourceCount) {
    return new EnrichmentEvent(
        topicId, EnrichmentJobKind.resource_refresh, EnrichmentStatus.ready, newResourceCount);
  }

  public static EnrichmentEvent refreshFailed(Long topicId) {
    return new EnrichmentEvent(
        topicId, EnrichmentJobKind.resource_refresh, EnrichmentStatus.failed, null);
  }
}
