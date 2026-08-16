package com.interviewprep.node.queue;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * A dedicated executor for {@link EnrichmentJobPoller}'s outer dispatch, kept separate from {@code
 * applicationTaskExecutor} (which {@code EnrichmentService} uses internally for its two producers).
 * Sharing one pool across both levels risks self-starvation: with an unbounded queue, a {@code
 * ThreadPoolExecutor} never grows past its core size in practice, so every core thread could end up
 * parked in {@code enrichTopic}'s {@code .join()}, waiting on producer sub-tasks that have no
 * thread left to run on.
 *
 * <p>This class also has to (re)define {@code applicationTaskExecutor} itself — confirmed by an
 * actual context-load failure, not a guess: Spring Boot's {@code TaskExecutionAutoConfiguration}
 * only creates that bean {@code @ConditionalOnMissingBean(Executor.class)}, evaluated against *any*
 * bean assignable to {@code Executor}. The moment {@link #enrichmentJobExecutor} existed anywhere
 * in the context, Boot silently stopped creating {@code applicationTaskExecutor} entirely, breaking
 * {@code EnrichmentService}'s constructor. Once one custom executor bean exists, every executor the
 * app needs has to be explicit.
 */
@Configuration
class EnrichmentQueueConfig {

  @Bean
  AsyncTaskExecutor applicationTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(8);
    executor.setMaxPoolSize(16);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("application-task-");
    executor.initialize();
    return executor;
  }

  @Bean
  AsyncTaskExecutor enrichmentJobExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(5);
    executor.setMaxPoolSize(10);
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("enrichment-job-");
    executor.initialize();
    return executor;
  }
}
