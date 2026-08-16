package com.interviewprep.node.service;

import com.interviewprep.node.entity.Concept;
import com.interviewprep.node.entity.ConceptOrigin;
import com.interviewprep.node.entity.EnrichmentStatus;
import com.interviewprep.node.entity.Resource;
import com.interviewprep.node.entity.Topic;
import com.interviewprep.node.repository.ConceptRepository;
import com.interviewprep.node.repository.ResourceRepository;
import com.interviewprep.node.repository.TopicRepository;
import com.interviewprep.node.service.llm.LabelNormalizer;
import com.interviewprep.node.service.llm.enrichment.ConceptGenerator;
import com.interviewprep.node.service.llm.enrichment.NonRetryableEnrichmentException;
import com.interviewprep.node.service.llm.enrichment.ProposedConcept;
import com.interviewprep.node.service.llm.enrichment.ProposedResource;
import com.interviewprep.node.service.llm.enrichment.ResourceFinder;
import com.interviewprep.node.service.llm.enrichment.RetryableEnrichmentException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fills a topic with resources and concepts once {@link ExpansionService} has decided it needs them
 * — this class never decides whether to run, only how to run it safely. Same
 * no-{@code @Transactional} shape as {@link ExpansionService} and for the same reason: {@link
 * ResourceFinder} and {@link ConceptGenerator} are agent/LLM calls that must never run while
 * holding a DB connection open.
 *
 * <p>Resource discovery and concept generation share only the topic's label and level, so they run
 * concurrently on {@link #executor} rather than back to back. Each producer's result is persisted
 * in its own all-or-nothing transaction as soon as it succeeds, and the topic only reaches {@code
 * ready} once both are confirmed complete — {@code ready} with half its content would let the
 * review layer quiz on concepts with no resources behind them.
 *
 * <p>Because a {@code failed} topic is claimable again, a retry must not redo finished work: before
 * launching either producer this checks whether that producer's rows already exist for the topic
 * and skips if so, so a retry resumes instead of redoing the (expensive) half that already
 * succeeded.
 */
@Service
public class EnrichmentService {

  private static final List<EnrichmentStatus> CLAIMABLE_STATUSES =
      List.of(EnrichmentStatus.pending, EnrichmentStatus.failed);

  private static final int MAX_ATTEMPTS = 3;
  private static final long INITIAL_BACKOFF_MS = 1500;

  private final TopicRepository topicRepository;
  private final ResourceRepository resourceRepository;
  private final ConceptRepository conceptRepository;
  private final ResourceFinder resourceFinder;
  private final ConceptGenerator conceptGenerator;
  private final LabelNormalizer labelNormalizer;
  private final ParentChainResolver parentChainResolver;
  private final AsyncTaskExecutor executor;
  private final TransactionTemplate requiredTemplate;
  private final TransactionTemplate requiresNewTemplate;

  EnrichmentService(
      TopicRepository topicRepository,
      ResourceRepository resourceRepository,
      ConceptRepository conceptRepository,
      ResourceFinder resourceFinder,
      ConceptGenerator conceptGenerator,
      LabelNormalizer labelNormalizer,
      ParentChainResolver parentChainResolver,
      @Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor,
      PlatformTransactionManager transactionManager) {
    this.topicRepository = topicRepository;
    this.resourceRepository = resourceRepository;
    this.conceptRepository = conceptRepository;
    this.resourceFinder = resourceFinder;
    this.conceptGenerator = conceptGenerator;
    this.labelNormalizer = labelNormalizer;
    this.parentChainResolver = parentChainResolver;
    this.executor = executor;
    this.requiredTemplate = new TransactionTemplate(transactionManager);
    this.requiresNewTemplate = new TransactionTemplate(transactionManager);
    this.requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Fills {@code topicId} with resources and concepts, or does nothing if it's already
   * enriching/ready/missing. Not wired to any caller yet. Void rather than a response DTO: this is
   * the future queue worker's entry point, not a request handler with a synchronous caller waiting
   * on a response. On unrecoverable failure the topic is marked {@code failed} and the triggering
   * exception is rethrown for the (future) worker to classify/dead-letter.
   */
  public void enrichTopic(Long topicId) {
    int claimed =
        requiredTemplate.execute(
            status ->
                topicRepository.claimForEnrichment(
                    topicId, EnrichmentStatus.enriching, CLAIMABLE_STATUSES));

    if (claimed == 0) {
      return; // doesn't exist, already enriching/ready, or another trigger already owns it
    }

    try {
      EnrichmentContext ctx = requiredTemplate.execute(status -> gatherContext(topicId));

      CompletableFuture<Outcome<List<ProposedResource>>> resourceFuture =
          launchResourceProducer(ctx);
      CompletableFuture<Outcome<List<ProposedConcept>>> conceptFuture = launchConceptProducer(ctx);
      // both futures created above before either is joined below -> concurrent, not sequential

      Outcome<List<ProposedResource>> resourceOutcome = resourceFuture.join();
      Outcome<List<ProposedConcept>> conceptOutcome = conceptFuture.join();

      boolean resourcesComplete = finishResources(topicId, resourceOutcome);
      boolean conceptsComplete = finishConcepts(topicId, conceptOutcome);

      if (!resourcesComplete || !conceptsComplete) {
        // Whatever the other producer returned is already persisted above, so a retry resumes
        // rather than redoing it.
        throw unwrap(
            resourceOutcome.isFailure() ? resourceOutcome.failure() : conceptOutcome.failure());
      }

      requiredTemplate.executeWithoutResult(
          status -> topicRepository.updateEnrichmentStatus(topicId, EnrichmentStatus.ready));
      // TODO: announce completion to the UI once a push channel (WebSocket/SSE) exists.
    } catch (RuntimeException e) {
      requiredTemplate.executeWithoutResult(
          status -> topicRepository.updateEnrichmentStatus(topicId, EnrichmentStatus.failed));
      throw e;
    }
  }

  private EnrichmentContext gatherContext(Long topicId) {
    Topic topic =
        topicRepository
            .findById(topicId)
            .orElseThrow(() -> new NoSuchElementException("topic not found: " + topicId));
    boolean resourcesAlreadyDone = !resourceRepository.findByTopicId(topicId).isEmpty();
    boolean conceptsAlreadyDone = !conceptRepository.findByTopicId(topicId).isEmpty();
    return new EnrichmentContext(
        topicId,
        topic.getLabel(),
        topic.getTrack().getLevel(),
        parentChainResolver.resolve(topicId),
        resourcesAlreadyDone,
        conceptsAlreadyDone);
  }

  private CompletableFuture<Outcome<List<ProposedResource>>> launchResourceProducer(
      EnrichmentContext ctx) {
    if (ctx.resourcesAlreadyDone()) {
      return CompletableFuture.completedFuture(Outcome.asSkipped());
    }
    return CompletableFuture.supplyAsync(
            () ->
                callWithRetry(
                    () ->
                        resourceFinder.findResources(ctx.topicId(), ctx.topicLabel(), ctx.level())),
            executor)
        .handle(Outcome::fromCompletion);
  }

  private CompletableFuture<Outcome<List<ProposedConcept>>> launchConceptProducer(
      EnrichmentContext ctx) {
    if (ctx.conceptsAlreadyDone()) {
      return CompletableFuture.completedFuture(Outcome.asSkipped());
    }
    return CompletableFuture.supplyAsync(
            () ->
                callWithRetry(
                    () ->
                        conceptGenerator.generateConcepts(
                            ctx.level(), ctx.topicLabel(), ctx.parentChainText())),
            executor)
        .handle(Outcome::fromCompletion);
  }

  /**
   * Retries up to {@link #MAX_ATTEMPTS} times with exponential backoff, but only on {@link
   * RetryableEnrichmentException}. Runs on the executor thread the caller submitted it to, so the
   * backoff sleep never blocks the orchestrating thread. Any other exception propagates on the
   * first attempt.
   */
  private <T> T callWithRetry(Supplier<T> call) {
    int attempt = 1;
    while (true) {
      try {
        return call.get();
      } catch (RetryableEnrichmentException e) {
        if (attempt == MAX_ATTEMPTS) {
          throw e;
        }
        sleepBackoff(attempt);
        attempt++;
      }
    }
  }

  private void sleepBackoff(int attempt) {
    long backoffMs = INITIAL_BACKOFF_MS * (1L << (attempt - 1)); // 500, 1000, 2000, ...
    try {
      Thread.sleep(backoffMs);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new NonRetryableEnrichmentException(
          "interrupted during enrichment retry backoff", interrupted);
    }
  }

  private boolean finishResources(Long topicId, Outcome<List<ProposedResource>> outcome) {
    if (outcome.skipped()) {
      return true;
    }
    if (outcome.isFailure()) {
      return false;
    }
    requiresNewTemplate.executeWithoutResult(
        status -> {
          resourceRepository.saveAllAndFlush(toResourceEntities(topicId, outcome.value()));
          topicRepository.updateSearchedAt(topicId, OffsetDateTime.now());
        });
    return true;
  }

  private boolean finishConcepts(Long topicId, Outcome<List<ProposedConcept>> outcome) {
    if (outcome.skipped()) {
      return true;
    }
    if (outcome.isFailure()) {
      return false;
    }
    requiresNewTemplate.executeWithoutResult(
        status -> conceptRepository.saveAllAndFlush(toConceptEntities(topicId, outcome.value())));
    return true;
  }

  private List<Resource> toResourceEntities(Long topicId, List<ProposedResource> proposals) {
    Topic topicRef = topicRepository.getReferenceById(topicId);
    List<Resource> rows = new ArrayList<>(proposals.size());
    int position = 0;
    for (ProposedResource p : proposals) {
      rows.add(
          new Resource(
              topicRef,
              p.type(),
              p.lane(),
              p.title(),
              p.url(),
              p.citation(),
              p.sourceQuery(),
              position++));
    }
    return rows;
  }

  private List<Concept> toConceptEntities(Long topicId, List<ProposedConcept> proposals) {
    Topic topicRef = topicRepository.getReferenceById(topicId);
    List<Concept> rows = new ArrayList<>(proposals.size());
    for (ProposedConcept p : proposals) {
      String normalizedLabel = labelNormalizer.normalize(p.label()); // deterministic, no LLM call
      rows.add(new Concept(topicRef, p.label(), normalizedLabel, ConceptOrigin.upfront));
    }
    return rows;
  }

  private static RuntimeException unwrap(Throwable t) {
    Throwable cause = (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
    return cause instanceof RuntimeException re ? re : new IllegalStateException(cause);
  }

  private record EnrichmentContext(
      Long topicId,
      String topicLabel,
      String level,
      String parentChainText,
      boolean resourcesAlreadyDone,
      boolean conceptsAlreadyDone) {}

  /**
   * Captures a producer's outcome without losing which one it was — a bare {@code
   * CompletableFuture.allOf(...).join()} can't distinguish "resources failed" from "concepts
   * failed." {@code skipped} means the resume check found this producer's work already done, so it
   * was never launched.
   */
  private record Outcome<T>(T value, Throwable failure, boolean skipped) {

    static <T> Outcome<T> fromCompletion(T value, Throwable failure) {
      return new Outcome<>(value, failure, false);
    }

    static <T> Outcome<T> asSkipped() {
      return new Outcome<>(null, null, true);
    }

    boolean isFailure() {
      return failure != null;
    }
  }
}
