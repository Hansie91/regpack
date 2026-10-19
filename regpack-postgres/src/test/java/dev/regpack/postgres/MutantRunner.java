package dev.regpack.postgres;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * Runs named test methods of one class through the JUnit launcher, as {@code
 * ContractSuiteTeethTest} of the in-memory module does for whole classes, and reports which of them
 * failed (by assertion or by exception). A test that was skipped by an assumption is not a failure.
 */
final class MutantRunner {
  /**
   * The result of one launcher run.
   *
   * @param found the number of test methods the launcher found for the selectors
   * @param failed the names of the test methods that failed
   */
  record Outcome(long found, Set<String> failed) {
    Outcome {
      failed = Set.copyOf(failed);
    }
  }

  private MutantRunner() {}

  /**
   * Runs exactly the methods {@code methodNames} of {@code binding} (an inherited default one too).
   */
  static Outcome run(Class<?> binding, Collection<String> methodNames) {
    List<DiscoverySelector> selectors = new ArrayList<>();
    for (String name : new TreeSet<>(methodNames)) {
      selectors.add(selectMethod(binding, name));
    }
    var request = LauncherDiscoveryRequestBuilder.request().selectors(selectors).build();
    var listener = new SummaryGeneratingListener();
    LauncherFactory.create().execute(request, listener);
    TestExecutionSummary summary = listener.getSummary();
    Set<String> failed = new TreeSet<>();
    for (TestExecutionSummary.Failure failure : summary.getFailures()) {
      if (failure.getTestIdentifier().getSource().orElse(null) instanceof MethodSource source) {
        failed.add(source.getMethodName());
      }
    }
    return new Outcome(summary.getTestsFoundCount(), failed);
  }
}
