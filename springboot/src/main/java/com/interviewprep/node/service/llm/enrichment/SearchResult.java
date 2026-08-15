package com.interviewprep.node.service.llm.enrichment;

/** One raw search hit, pre-validation, pre-classification. */
public record SearchResult(String title, String url, String snippet) {}
