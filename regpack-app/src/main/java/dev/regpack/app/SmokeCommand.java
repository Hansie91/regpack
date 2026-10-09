package dev.regpack.app;

import dev.regpack.engine.explain.Tier1Explainer;
import dev.regpack.engine.pipeline.MicroBatchPipeline;
import dev.regpack.engine.skeleton.WalkingSkeletonRegime;
import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.inmemory.InMemorySourceConnector;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.Outcome;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.CanonicalExecutionCodec;
import dev.regpack.model.decision.DecisionCodec;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.model.json.Json;
import dev.regpack.ports.Clock;
import dev.regpack.ports.connector.SourceConnector.BatchStream;
import dev.regpack.ports.connector.SourceConnector.ReadLimits;
import dev.regpack.ports.connector.SourceConnector.ReadRequest;
import dev.regpack.ports.evidence.EvidenceStore.Retention;
import dev.regpack.ports.evidence.EvidenceStore.RetentionClass;
import dev.regpack.ports.exec.CostBudget;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code smoke} command: optional health probes of a running service, then the walking skeleton
 * end to end over the bundled toy executions with in-memory adapters.
 */
public final class SmokeCommand {
  private static final String USAGE = "usage: regpack smoke [--health-url <base>]";
  private static final String RESOURCE = "/smoke/toy-executions.ndjson";
  private static final String SOURCE_ID = "toy";
  private static final String STREAM = "trades";
  private static final String MEDIA_TYPE = "application/x-ndjson";
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
  private static final List<String> HEALTH_PATHS = List.of("/health/live", "/health/ready");
  private static final List<Outcome> EXPECTED =
      List.of(Outcome.REPORT, Outcome.DO_NOT_REPORT, Outcome.UNDETERMINED);

  private SmokeCommand() {}

  /** Runs the smoke command: 0 ok, 1 failure, 2 usage. */
  public static int run(List<String> args, PrintStream out, PrintStream err, Clock clock) {
    return run(args, out, err, clock, bundledInput());
  }

  static int run(List<String> args, PrintStream out, PrintStream err, Clock clock, String ndjson) {
    Objects.requireNonNull(args, "args");
    Objects.requireNonNull(out, "out");
    Objects.requireNonNull(err, "err");
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(ndjson, "ndjson");
    Optional<String> healthUrl = Optional.empty();
    if (args.size() == 2 && args.get(0).equals("--health-url")) {
      healthUrl = Optional.of(args.get(1));
    } else if (!args.isEmpty()) {
      err.println(USAGE);
      return 2;
    }
    try {
      if (healthUrl.isPresent() && !probeHealth(healthUrl.get(), out, err)) {
        return 1;
      }
      return runPipeline(out, err, clock, ndjson);
    } catch (RuntimeException e) {
      err.println("regpack smoke: FAILED " + e);
      return 1;
    }
  }

  private static boolean probeHealth(String base, PrintStream out, PrintStream err) {
    String root = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    for (String path : HEALTH_PATHS) {
      try {
        HttpRequest request =
            HttpRequest.newBuilder(URI.create(root + path)).timeout(HTTP_TIMEOUT).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || !response.body().contains("\"status\":\"UP\"")) {
          err.println(
              "regpack smoke: FAILED health "
                  + path
                  + " status "
                  + response.statusCode()
                  + " body "
                  + response.body());
          return false;
        }
        out.println("regpack smoke: health " + path + " OK");
      } catch (IOException e) {
        err.println("regpack smoke: FAILED health " + path + " " + e);
        return false;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        err.println("regpack smoke: FAILED health " + path + " interrupted");
        return false;
      }
    }
    return true;
  }

  private static int runPipeline(PrintStream out, PrintStream err, Clock clock, String ndjson) {
    InMemoryEvidenceStore evidence = new InMemoryEvidenceStore(clock);
    InMemoryLedgerStore ledger = new InMemoryLedgerStore(clock);
    InMemorySourceConnector source =
        new InMemorySourceConnector(
            SOURCE_ID, STREAM, MEDIA_TYPE, List.of(ndjson.getBytes(StandardCharsets.UTF_8)));
    MicroBatchPipeline pipeline =
        new MicroBatchPipeline(
            clock,
            evidence,
            ledger,
            WalkingSkeletonRegime.standard(),
            WalkingSkeletonRegime.skeletonContext(),
            new Retention(
                new RetentionClass("landing"), clock.now().plus(Duration.ofDays(365 * 10L))),
            new CostBudget(100));
    try (BatchStream stream =
        source.read(new ReadRequest(Optional.empty(), new ReadLimits(100, 1_000_000L)))) {
      pipeline.pump(stream, SOURCE_ID, 10);
    }

    List<Outcome> outcomes = new ArrayList<>();
    for (EventIdentity identity : eventIdentities(ndjson)) {
      DecisionRecord decision =
          ledger
              .currentDecision(WalkingSkeletonRegime.REGIME, identity)
              .orElseThrow(() -> new IllegalStateException("no decision for " + identity.value()));
      out.println("decision " + Json.canonical(DecisionCodec.recordToMap(decision)));
      for (String line : Tier1Explainer.explain(decision).split("\n", -1)) {
        out.println("explain: " + line);
      }
      outcomes.add(decision.body().outcome());
    }
    if (!outcomes.equals(EXPECTED)) {
      err.println("regpack smoke: FAILED expected " + EXPECTED + " got " + outcomes);
      return 1;
    }
    out.println("regpack smoke: OK decisions=" + outcomes.size());
    return 0;
  }

  /** The event identities in file order. */
  private static List<EventIdentity> eventIdentities(String ndjson) {
    List<EventIdentity> identities = new ArrayList<>();
    for (Object value : Json.parseNdjson(ndjson)) {
      if (!(value instanceof Map<?, ?> raw)) {
        throw new IllegalArgumentException("each NDJSON line must be an object");
      }
      Map<String, Object> map = new LinkedHashMap<>();
      raw.forEach((k, v) -> map.put((String) k, v));
      map.remove("sourceRecordId");
      map.remove("sourceVersion");
      CanonicalExecution execution = CanonicalExecutionCodec.fromMap(map);
      identities.add(execution.eventIdentity());
    }
    return List.copyOf(identities);
  }

  private static String bundledInput() {
    try (InputStream in = SmokeCommand.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("missing resource " + RESOURCE);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
