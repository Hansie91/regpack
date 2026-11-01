package dev.regpack.flow.ingest;

import dev.regpack.model.canonical.CanonicalExecution;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A source record after the mapping: the key of the record in its source, the canonical execution,
 * and the attributes the canonical event does not hold (a lookup key for enrichment, such as an
 * account). The attributes are in key order. Nothing here is printed: {@link #toString} carries no
 * field value (SF8).
 *
 * @param sourceRecordId the id of the record in its source, a key text
 * @param sourceVersion the version of the record, 0 or more
 * @param execution the canonical execution
 * @param attributes the mapped values outside the canonical event, by name, only those present
 */
public record MappedRecord(
    String sourceRecordId,
    long sourceVersion,
    CanonicalExecution execution,
    Map<String, String> attributes) {
  public MappedRecord {
    Objects.requireNonNull(sourceRecordId, "sourceRecordId");
    if (sourceVersion < 0) {
      throw new IllegalArgumentException("MappedRecord sourceVersion must be >= 0");
    }
    Objects.requireNonNull(execution, "execution");
    attributes = Collections.unmodifiableMap(new TreeMap<>(attributes));
  }

  @Override
  public String toString() {
    return "MappedRecord[sourceVersion=" + sourceVersion + "]";
  }
}
