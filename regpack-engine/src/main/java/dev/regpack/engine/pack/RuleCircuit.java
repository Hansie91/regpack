package dev.regpack.engine.pack;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The per-rule poison circuit (spec 7.3, plan T17): after {@code n} failed evaluations of one rule
 * in a window of {@code m} evaluations of that rule, the circuit of the rule opens.
 *
 * <p>The windows are tumbling and count evaluations of the rule, never time (spec 14.1): the first
 * {@code m} evaluations of a rule are window 1, the next {@code m} window 2, and so on, and a
 * window that ends without {@code n} failures is forgotten. The rule opens when its window holds
 * {@code n} failures, so the same sequence of {@link #record} calls always gives the same state.
 * Defaults are N = 4 in M = 50 (SP-R, MODELLED).
 *
 * <p>An open circuit closes only by an explicit {@link #close} (open item OI-4, default): there is
 * no timer, no half-open probing and no automatic recovery, and a new pack is a new {@code
 * PackRegime} with a new circuit. While a rule is open nothing is counted for it, so {@link
 * #record} for an open rule changes nothing.
 *
 * <p>Every close of an open rule raises the {@link #epoch()}, which {@link PackRegime} puts into
 * the decision context (INTERP-3, option A) so that the ledger supersedes a decision made under the
 * open circuit.
 *
 * <p>The circuit is thread-safe: each rule has its own state, updated under that state's lock, so
 * no count is lost under concurrent evaluation. Which evaluation of several concurrent ones is the
 * n-th depends on the interleaving; outcomes under concurrent evaluation are outside I3.
 */
public final class RuleCircuit {
  /** Default number of failures that open a circuit (SP-R, MODELLED). */
  public static final int DEFAULT_FAILURES = 4;

  /** Default window of evaluations (SP-R, MODELLED). */
  public static final int DEFAULT_WINDOW = 50;

  private static final class State {
    private int evaluations;
    private int failures;
    private volatile boolean open;
  }

  private final int n;
  private final int m;
  private final Map<String, State> states = new ConcurrentHashMap<>();
  private final AtomicInteger openEvents = new AtomicInteger();
  private final AtomicLong epoch = new AtomicLong();

  /**
   * @param n failures in one window that open the circuit of a rule
   * @param m evaluations in one window
   * @throws IllegalArgumentException unless {@code 1 <= n <= m}
   */
  public RuleCircuit(int n, int m) {
    if (n < 1 || m < n) {
      throw new IllegalArgumentException("a circuit needs 1 <= n <= m, got n=" + n + " m=" + m);
    }
    this.n = n;
    this.m = m;
  }

  /** N = 4 failures in M = 50 evaluations. */
  public static RuleCircuit defaults() {
    return new RuleCircuit(DEFAULT_FAILURES, DEFAULT_WINDOW);
  }

  /** Whether the circuit of the rule is open. A rule never recorded is closed. */
  public boolean isOpen(String ruleId) {
    requireRule(ruleId);
    State state = states.get(ruleId);
    return state != null && state.open;
  }

  /**
   * Records one evaluation of the rule. Does nothing while the rule's circuit is open.
   *
   * @param failed whether the evaluation ended in an expression error or a budget overrun in this
   *     rule
   */
  public void record(String ruleId, boolean failed) {
    requireRule(ruleId);
    State state = states.computeIfAbsent(ruleId, id -> new State());
    synchronized (state) {
      if (state.open) {
        return;
      }
      state.evaluations++;
      if (failed) {
        state.failures++;
      }
      if (state.failures >= n) {
        state.open = true;
        openEvents.incrementAndGet();
      } else if (state.evaluations >= m) {
        state.evaluations = 0;
        state.failures = 0;
      }
    }
  }

  /** Closes the circuit of the rule and starts a fresh window; a rule never recorded is ignored. */
  public void close(String ruleId) {
    requireRule(ruleId);
    State state = states.get(ruleId);
    if (state == null) {
      return;
    }
    synchronized (state) {
      if (state.open) {
        epoch.incrementAndGet();
      }
      state.open = false;
      state.evaluations = 0;
      state.failures = 0;
    }
  }

  /**
   * The generation of this circuit: 0 at creation, plus 1 for every {@link #close} of a rule that
   * was open; the close of a closed or never recorded rule changes nothing, and the value never
   * decreases. {@link PackRegime} stamps it into the decision context (INTERP-3, option A), so a
   * re-evaluation after a close is a different decision context. The epoch lives in this object and
   * so in this process: a new circuit starts again at 0 (D-L1).
   */
  public long epoch() {
    return epoch.get();
  }

  /** How many times a circuit has opened, counting a rule that was closed and opened again. */
  public int openEvents() {
    return openEvents.get();
  }

  /** The rules whose circuit is open now, in natural order; a copy. */
  public Set<String> openRules() {
    TreeSet<String> open = new TreeSet<>();
    for (Map.Entry<String, State> entry : states.entrySet()) {
      if (entry.getValue().open) {
        open.add(entry.getKey());
      }
    }
    return Collections.unmodifiableSortedSet(open);
  }

  private static void requireRule(String ruleId) {
    if (Objects.requireNonNull(ruleId, "ruleId").isBlank()) {
      throw new IllegalArgumentException("ruleId must not be blank");
    }
  }
}
