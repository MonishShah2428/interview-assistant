package com.interviewprep.node.service.llm.enrichment;

/**
 * A concept {@link ConceptGenerator} proposes. Just a label — {@code origin} is always {@code
 * upfront} at this call site, and normalization reuses {@link
 * com.interviewprep.node.service.llm.LabelNormalizer}.
 */
public record ProposedConcept(String label) {}
