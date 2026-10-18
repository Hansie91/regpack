package dev.regpack.spikes.r.bench;

import dev.regpack.bench.proto.Workload;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs several workloads at the same time, one platform thread each, and sums their units: a call
 * of {@code runUnits(n)} shares the {@code n} units out over the threads and returns when the
 * slowest has finished, so the rate of a window is the aggregate rate of all threads. The checksum
 * is the sum of the workloads' checksums. Close it to stop the threads.
 */
public final class ParallelWorkload implements Workload, AutoCloseable {
  private final List<Workload> workloads;
  private final ExecutorService pool;

  public ParallelWorkload(List<? extends Workload> workloads) {
    if (workloads.isEmpty()) {
      throw new IllegalArgumentException("a parallel workload needs at least one thread");
    }
    this.workloads = List.copyOf(workloads);
    AtomicInteger counter = new AtomicInteger();
    ThreadFactory factory =
        task -> {
          Thread thread = new Thread(task, "spike-r-bench-" + counter.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        };
    this.pool = Executors.newFixedThreadPool(this.workloads.size(), factory);
  }

  @Override
  public long runUnits(long maxUnits) {
    if (maxUnits < 0) {
      throw new IllegalArgumentException("maxUnits must not be negative: " + maxUnits);
    }
    int threads = workloads.size();
    long share = maxUnits / threads;
    long extra = maxUnits % threads;
    List<Future<Long>> futures = new ArrayList<>(threads);
    for (int i = 0; i < threads; i++) {
      Workload workload = workloads.get(i);
      long units = share + (i < extra ? 1 : 0);
      futures.add(pool.submit(() -> workload.runUnits(units)));
    }
    long done = 0;
    RuntimeException failure = null;
    for (Future<Long> future : futures) {
      try {
        done += future.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while a benchmark thread ran", e);
      } catch (ExecutionException e) {
        if (failure == null) {
          Throwable cause = e.getCause();
          failure =
              cause instanceof RuntimeException r
                  ? r
                  : new IllegalStateException("a benchmark thread failed", cause);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
    return done;
  }

  @Override
  public long checksum() {
    long sum = 0;
    for (Workload workload : workloads) {
      sum += workload.checksum();
    }
    return sum;
  }

  @Override
  public void close() {
    pool.shutdownNow();
  }
}
