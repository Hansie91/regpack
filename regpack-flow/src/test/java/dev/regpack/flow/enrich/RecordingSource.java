package dev.regpack.flow.enrich;

import dev.regpack.ports.connector.CheckResult;
import dev.regpack.ports.connector.ConnectorSpec;
import dev.regpack.ports.connector.EnrichmentSource;
import java.util.ArrayList;
import java.util.List;

/** Test helper: a source that remembers every request it was asked and passes it on. */
final class RecordingSource implements EnrichmentSource {
  private final EnrichmentSource inner;
  private final List<Request> requests = new ArrayList<>();

  RecordingSource(EnrichmentSource inner) {
    this.inner = inner;
  }

  /** Every request received so far, in order. */
  List<Request> requests() {
    return List.copyOf(requests);
  }

  /** The keys of every request, in order. */
  List<List<String>> requestedKeys() {
    return requests.stream().map(Request::keys).toList();
  }

  @Override
  public ConnectorSpec spec() {
    return inner.spec();
  }

  @Override
  public CheckResult check() {
    return inner.check();
  }

  @Override
  public Limits limits() {
    return inner.limits();
  }

  @Override
  public Result lookup(Request request) {
    requests.add(request);
    return inner.lookup(request);
  }
}
