package com.interviewprep.node.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.interviewprep.node.entity.EnrichmentJob;
import com.interviewprep.node.entity.EnrichmentJobKind;
import com.interviewprep.node.repository.EnrichmentJobRepository;
import com.interviewprep.node.repository.EnrichmentJobRepository.ClaimedJob;
import com.interviewprep.node.service.EnrichmentService;
import com.interviewprep.node.sse.EnrichmentEventPublisher;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class EnrichmentJobPollerTest {

  @Test
  void pollsMostRecentlyRequestedFirstWithoutInterruptingInFlightJob() throws Exception {
    Deque<ClaimedJob> lifoStack = new ArrayDeque<>();
    AtomicInteger claimBatchCalls = new AtomicInteger();

    EnrichmentJobRepository jobRepository = mock(EnrichmentJobRepository.class);
    when(jobRepository.claimBatch(anyInt(), anyInt()))
        .thenAnswer(
            inv -> {
              claimBatchCalls.incrementAndGet();
              int limit = inv.getArgument(0);
              List<ClaimedJob> claimed = new ArrayList<>();
              synchronized (lifoStack) {
                while (claimed.size() < limit && !lifoStack.isEmpty()) {
                  claimed.add(lifoStack.pop());
                }
              }
              return claimed;
            });

    CountDownLatch aStarted = new CountDownLatch(1);
    CountDownLatch allowAFinish = new CountDownLatch(1);
    AtomicBoolean aInterruptedDuringRun = new AtomicBoolean();
    List<Long> executionOrder = new CopyOnWriteArrayList<>();

    AtomicReference<CountDownLatch> currentTaskFinished =
        new AtomicReference<>(new CountDownLatch(1));

    EnrichmentService enrichmentService = mock(EnrichmentService.class);
    doAnswer(
            inv -> {
              Long topicId = inv.getArgument(0);
              if (topicId.equals(101L)) {
                aStarted.countDown();
                assertThat(allowAFinish.await(5, TimeUnit.SECONDS)).isTrue();
                aInterruptedDuringRun.set(Thread.currentThread().isInterrupted());
              }
              executionOrder.add(topicId);
              return null;
            })
        .when(enrichmentService)
        .enrichTopic(any());

    PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

    ThreadPoolTaskExecutor delegateExecutor = new ThreadPoolTaskExecutor();
    delegateExecutor.setCorePoolSize(2);
    delegateExecutor.setMaxPoolSize(2);
    delegateExecutor.setQueueCapacity(10);
    delegateExecutor.setThreadNamePrefix("poller-test-");
    delegateExecutor.initialize();

    AsyncTaskExecutor trackingExecutor =
        task ->
            delegateExecutor.execute(
                () -> {
                  try {
                    task.run();
                  } finally {
                    currentTaskFinished.get().countDown();
                  }
                });

    try {
      EnrichmentEventPublisher eventPublisher = mock(EnrichmentEventPublisher.class);
      EnrichmentJobPoller poller =
          new EnrichmentJobPoller(
              jobRepository,
              enrichmentService,
              trackingExecutor,
              txManager,
              eventPublisher,
              1,
              false);

      // Push job A and start it.
      synchronized (lifoStack) {
        lifoStack.push(job(1L, 101L));
      }
      poller.poll();
      assertThat(aStarted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(claimBatchCalls.get()).isEqualTo(1);

      // While A is still running, push B then C and trigger poll() twice.
      synchronized (lifoStack) {
        lifoStack.push(job(2L, 102L));
        lifoStack.push(job(3L, 103L));
      }
      poller.poll();
      poller.poll();

      // Active job is never interrupted, and no new claim runs while inFlight == 1.
      assertThat(claimBatchCalls.get()).isEqualTo(1);
      assertThat(executionOrder).isEmpty();

      // Release A and wait until the CompletableFuture + whenComplete(inFlight.decrementAndGet)
      // finish.
      allowAFinish.countDown();
      assertThat(currentTaskFinished.get().await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(aInterruptedDuringRun.get()).isFalse();
      assertThat(executionOrder).containsExactly(101L);

      // Next poll claims C (last requested), not B.
      currentTaskFinished.set(new CountDownLatch(1));
      poller.poll();
      assertThat(currentTaskFinished.get().await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(executionOrder).containsExactly(101L, 103L);

      // Final poll claims B.
      currentTaskFinished.set(new CountDownLatch(1));
      poller.poll();
      assertThat(currentTaskFinished.get().await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(executionOrder).containsExactly(101L, 103L, 102L);
    } finally {
      delegateExecutor.shutdown();
    }
  }

  @Test
  void requiresActiveSubscriberSkipsDbWhenEmptyAndScopesClaimsToActiveTracks() throws Exception {
    EnrichmentJobRepository jobRepository = mock(EnrichmentJobRepository.class);
    when(jobRepository.claimBatchForTracks(anyInt(), anyInt(), eq(Set.of(42L))))
        .thenReturn(List.of(job(9L, 202L)));

    CountDownLatch ran = new CountDownLatch(1);
    EnrichmentService enrichmentService = mock(EnrichmentService.class);
    doAnswer(
            inv -> {
              ran.countDown();
              return null;
            })
        .when(enrichmentService)
        .enrichTopic(202L);

    PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

    EnrichmentEventPublisher eventPublisher = mock(EnrichmentEventPublisher.class);
    when(eventPublisher.activeTrackIds()).thenReturn(Set.of());

    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.initialize();

    try {
      EnrichmentJobPoller poller =
          new EnrichmentJobPoller(
              jobRepository, enrichmentService, executor, txManager, eventPublisher, 1, true);

      poller.poll();
      verifyNoInteractions(jobRepository);

      when(eventPublisher.activeTrackIds()).thenReturn(Set.of(42L));
      poller.poll();
      assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
      verify(jobRepository).claimBatchForTracks(1, EnrichmentJob.MAX_ATTEMPTS, Set.of(42L));
    } finally {
      executor.shutdown();
    }
  }

  private static ClaimedJob job(long id, long topicId) {
    return new ClaimedJob() {
      @Override
      public Long getId() {
        return id;
      }

      @Override
      public Long getTopicId() {
        return topicId;
      }

      @Override
      public String getKind() {
        return EnrichmentJobKind.initial.name();
      }
    };
  }
}
