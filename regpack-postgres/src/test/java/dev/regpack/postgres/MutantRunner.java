package dev.regpack.postgres;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 * failed (by assertion or by exception), with the text of each failure. A test that was skipped by
 * an assumption is not a failure.
 */
final class MutantRunner {
  /**
   * The result of one launcher run.
   *
   * @param found the number of test methods the launcher found for the selectors
   * @param failures the failure text of each test method that failed, by method name: the class
   *     name and message of every exception of the failure and of its causes, one per line
   */
  record Outcome(long found, Map<String, String> failures) {
    Outcome {
      failures = Map.copyOf(failures);
    }

    /** The names of the test methods that failed. */
    Set<String> failed() {
      return failures.keySet();
    }
  }

  /** The longest cause chain written into a failure text. */
  private static final int MAX_CAUSES = 6;

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
    Map<String, String> failures = new TreeMap<>();
    for (TestExecutionSummary.Failure failure : summary.getFailures()) {
      if (failure.getTestIdentifier().getSource().orElse(null) instanceof MethodSource source) {
        failures.merge(
            source.getMethodName(), describe(failure.getException()), (a, b) -> a + "\n" + b);
      }
    }
    return new Outcome(summary.getTestsFoundCount(), failures);
  }

  /** Class name and message of the exception and of its causes, one per line. */
  static String describe(Throwable failure) {
    StringBuilder text = new StringBuilder();
    Throwable current = failure;
    for (int depth = 0; current != null && depth < MAX_CAUSES; depth++) {
      if (depth > 0) {
        text.append('\n');
      }
      text.append(current.getClass().getName()).append(": ").append(current.getMessage());
      current = current.getCause();
    }
    return text.toString();
  }
}
