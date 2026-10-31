package dev.regpack.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.RetentionLedgerContract;
import dev.regpack.testkit.contract.SubjectIndexLedgerContract;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The walks of the retention and subject-index contract suites must end a paging flaw at once. A
 * keyset that does not move past its bound (a {@code >=} where a {@code >} belongs) makes a walk
 * ask for the same page for ever; on a real server every page costs a connection, and a walk that
 * runs to a guard of thousands of pages turns a red test into a job that ends by its time limit
 * without a report. Here the flaw is a ledger that ignores the bound of every page, and the
 * contract methods that walk are called directly: each must fail at its second page.
 */
class ContractWalksFailFastTest {
  /** More than any honest walk of these methods needs, far fewer than the old guards (2,000). */
  private static final int MOST_READS = 30;

  /** A ledger over the in-memory one whose paged reads all start at the beginning. */
  private static LedgerStore ignoringTheBound(ManualClock clock, AtomicInteger pagedReads) {
    InMemoryLedgerStore real = new InMemoryLedgerStore(clock);
    return (LedgerStore)
        Proxy.newProxyInstance(
            LedgerStore.class.getClassLoader(),
            new Class<?>[] {LedgerStore.class},
            (proxy, method, args) -> {
              Object[] used = args;
              String name = method.getName();
              if (name.equals("retentionRows")
                  || name.equals("expiredRetention")
                  || name.equals("subjectRefs")) {
                pagedReads.incrementAndGet();
                used = args.clone();
                used[1] = Optional.empty(); // the second parameter of the three is the bound
              }
              try {
                return method.invoke(real, used);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  private static ManualClock clock() {
    return new ManualClock(Instant.parse("2026-10-09T08:00:00Z"));
  }

  @Test
  void aWalkOfRetentionRowsThatDoesNotAdvanceFailsAtItsSecondPage() {
    ManualClock clock = clock();
    AtomicInteger reads = new AtomicInteger();
    LedgerStore flawed = ignoringTheBound(clock, reads);
    RetentionLedgerContract contract =
        new RetentionLedgerContract() {
          @Override
          public LedgerStore ledger() {
            return flawed;
          }

          @Override
          public ManualClock clock() {
            return clock;
          }
        };

    assertThatThrownBy(contract::rowsOfOneArtifactAreNeverSplitAcrossPages)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("does not advance");
    assertThat(reads.get()).isLessThanOrEqualTo(MOST_READS);
  }

  @Test
  void aWalkOfTheExpiryListThatDoesNotAdvanceFailsAtItsSecondPage() {
    ManualClock clock = clock();
    AtomicInteger reads = new AtomicInteger();
    LedgerStore flawed = ignoringTheBound(clock, reads);
    RetentionLedgerContract contract =
        new RetentionLedgerContract() {
          @Override
          public LedgerStore ledger() {
            return flawed;
          }

          @Override
          public ManualClock clock() {
            return clock;
          }
        };

    assertThatThrownBy(contract::walkingExpiredRetentionInPagesOfThreeReturnsEveryDueRowOnce)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("does not advance");
    assertThat(reads.get()).isLessThanOrEqualTo(MOST_READS);
  }

  @Test
  void aWalkOfSubjectReferencesThatDoesNotAdvanceFailsAtItsSecondPage() {
    AtomicInteger reads = new AtomicInteger();
    LedgerStore flawed = ignoringTheBound(clock(), reads);
    SubjectIndexLedgerContract contract = () -> flawed;

    assertThatThrownBy(contract::walkingThePagesWithAfterReturnsAllRefsOnceAndInOrder)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("does not advance");
    assertThat(reads.get()).isLessThanOrEqualTo(MOST_READS);
  }
}
