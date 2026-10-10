package dev.regpack.engine.pipeline;

import dev.regpack.inmemory.InMemorySourceConnector;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.canonical.AssetClass;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.CanonicalExecutionCodec;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.model.json.Json;
import dev.regpack.ports.connector.SourceConnector.ReadLimits;
import dev.regpack.ports.connector.SourceConnector.ReadRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Shared fixture of the micro-batch pipeline tests: two NDJSON batches over three trades. */
final class ToyBatches {
  static final String SOURCE_ID = "toy";
  static final String STREAM = "trades";
  static final String MEDIA_TYPE = "application/x-ndjson";
  static final Instant TRADED_AT = Instant.parse("2026-10-09T09:30:00.123456Z");

  static final EventIdentity T1 = EventIdentity.of("LEI-FIRM-0001", "XPAR", "EX-0001");
  static final EventIdentity T2 = EventIdentity.of("LEI-FIRM-0001", "XPAR", "EX-0002");
  static final EventIdentity T3 = EventIdentity.of("LEI-FIRM-0001", "XPAR", "EX-0003");

  private ToyBatches() {}

  static CanonicalExecution execution(
      String venueExecutionId, LifecycleKind kind, PartyKind clientKind) {
    return new CanonicalExecution(
        "LEI-FIRM-0001",
        "XPAR",
        venueExecutionId,
        kind,
        TRADED_AT,
        AssetClass.EQUITY,
        "ZZ0000000001",
        true,
        clientKind,
        clientKind == PartyKind.LEGAL_ENTITY ? Optional.of("LEI-CLIENT-0001") : Optional.empty(),
        new BigDecimal("100"),
        new BigDecimal("12.25"),
        "EUR");
  }

  /** One NDJSON line: the canonical execution map plus sourceRecordId and sourceVersion. */
  static String line(String sourceRecordId, long sourceVersion, CanonicalExecution execution) {
    Map<String, Object> map = new LinkedHashMap<>(CanonicalExecutionCodec.toMap(execution));
    map.put("sourceRecordId", sourceRecordId);
    map.put("sourceVersion", sourceVersion);
    return Json.canonical(map);
  }

  static byte[] payload(String... lines) {
    return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
  }

  /** T-1 (equity, new), T-2 (novation), T-3 (natural person). */
  static byte[] batch1() {
    return payload(
        line("T-1", 1, execution("EX-0001", LifecycleKind.NEW, PartyKind.LEGAL_ENTITY)),
        line("T-2", 1, execution("EX-0002", LifecycleKind.NOVATION, PartyKind.LEGAL_ENTITY)),
        line("T-3", 1, execution("EX-0003", LifecycleKind.NEW, PartyKind.NATURAL_PERSON)));
  }

  /** T-1 version 2 as a novation (supersedes the REPORT) and T-2 version 1 again (a no-op). */
  static byte[] batch2() {
    return payload(
        line("T-1", 2, execution("EX-0001", LifecycleKind.NOVATION, PartyKind.LEGAL_ENTITY)),
        line("T-2", 1, execution("EX-0002", LifecycleKind.NOVATION, PartyKind.LEGAL_ENTITY)));
  }

  static InMemorySourceConnector source(byte[]... payloads) {
    List<byte[]> list = new ArrayList<>(List.of(payloads));
    return new InMemorySourceConnector("toy-source", STREAM, MEDIA_TYPE, list);
  }

  static InMemorySourceConnector standardSource() {
    return source(batch1(), batch2());
  }

  static ReadRequest fromStart() {
    return new ReadRequest(Optional.empty(), new ReadLimits(100, 1_000_000L));
  }
}
