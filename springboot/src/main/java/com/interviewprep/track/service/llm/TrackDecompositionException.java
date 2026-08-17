package com.interviewprep.track.service.llm;

/**
 * A {@link TrackDecomposer} failure — model timeout, unparseable output, or (for now) simply "not
 * implemented yet." {@code TrackController} maps this to a {@code 502}: an upstream problem, not
 * the user's.
 */
public class TrackDecompositionException extends RuntimeException {

  public TrackDecompositionException(String message) {
    super(message);
  }

  public TrackDecompositionException(String message, Throwable cause) {
    super(message, cause);
  }
}
