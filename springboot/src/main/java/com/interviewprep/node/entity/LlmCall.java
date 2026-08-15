package com.interviewprep.node.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;

/**
 * Audit log for every LLM call in the pipeline. {@code entity_type}/{@code entity_id} are a
 * polymorphic pair with no DB-level FK — they're for lookup, not referential integrity.
 */
@Getter
@Entity
@Table(name = "llm_call")
public class LlmCall {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "call_name", nullable = false, updatable = false)
  private String callName;

  @Column(name = "entity_type", updatable = false)
  private String entityType;

  @Column(name = "entity_id", updatable = false)
  private Long entityId;

  @Column(nullable = false, updatable = false)
  private String model;

  @Column(nullable = false, updatable = false)
  private String prompt;

  @Column(updatable = false)
  private String response;

  @Column(updatable = false)
  private String error;

  @Column(name = "latency_ms", updatable = false)
  private Integer latencyMs;

  @Column(name = "input_tokens", updatable = false)
  private Integer inputTokens;

  @Column(name = "output_tokens", updatable = false)
  private Integer outputTokens;

  @Column(name = "cost_usd", updatable = false)
  private BigDecimal costUsd;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  LlmCall() {}

  public LlmCall(
      String callName,
      String entityType,
      Long entityId,
      String model,
      String prompt,
      String response,
      String error,
      Integer latencyMs,
      Integer inputTokens,
      Integer outputTokens,
      BigDecimal costUsd) {
    this.callName = callName;
    this.entityType = entityType;
    this.entityId = entityId;
    this.model = model;
    this.prompt = prompt;
    this.response = response;
    this.error = error;
    this.latencyMs = latencyMs;
    this.inputTokens = inputTokens;
    this.outputTokens = outputTokens;
    this.costUsd = costUsd;
    this.createdAt = OffsetDateTime.now();
  }
}
