package dev.regpack.bench.proto;

import java.util.List;
import java.util.Objects;

/**
 * The forks of one series under one protocol.
 *
 * @param forks every fork that was started, discarded ones included, in order
 * @param protocol the protocol they ran under
 */
public record ForkSeries(List<ForkResult> forks, Protocol protocol) {
  public ForkSeries {
    forks = List.copyOf(forks);
    Objects.requireNonNull(protocol, "protocol");
  }

  /** The forks that count: those not discarded. */
  public List<ForkResult> kept() {
    return forks.stream().filter(f -> !f.discarded()).toList();
  }

  /** The discarded forks with their reasons; they are always listed in a report (P-5). */
  public List<ForkResult> discarded() {
    return forks.stream().filter(ForkResult::discarded).toList();
  }

  /** More discards than the protocol allows make the series INCONCLUSIVE (P-5). */
  public boolean tooManyDiscards() {
    return discarded().size() > protocol.maxDiscards();
  }

  /** The share of kept forks whose warm-up never settled (P-2); 0 when no fork is kept. */
  public double unstableFraction() {
    List<ForkResult> kept = kept();
    if (kept.isEmpty()) {
      return 0.0;
    }
    long unstable = kept.stream().filter(ForkResult::unstable).count();
    return (double) unstable / kept.size();
  }
}
