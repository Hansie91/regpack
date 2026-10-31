package dev.regpack.engine.pack;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.canonical.AssetClass;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.model.canonical.Side;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.model.LoadResult;
import dev.regpack.pack.model.LoadedPack;
import dev.regpack.pack.model.PackLoader;
import dev.regpack.pack.source.Checksums;
import dev.regpack.pack.source.PackSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Test helper: the committed fixture pack {@code derive-fixture} (a scope table and a derive table
 * over the optional canonical paths) as a map of file to text, and sealed variants of it, as {@link
 * SamplePacks} does for the sample pack.
 */
final class DeriveFixture {
  static final String REPORT = "derive.fixture";
  static final String DERIVE = "derive/report.yaml";
  static final String SCOPE = "scope/scope.yaml";
  static final String PACK = "pack.yaml";

  private DeriveFixture() {}

  static Path directory() {
    return Path.of(System.getProperty("regpack.repo.root"))
        .resolve("regpack-engine/src/test/resources/packs/derive-fixture");
  }

  /** Every file of the fixture except {@code CHECKSUMS}, as text. */
  static Map<String, String> files() {
    PackSource source = PackSource.fromDirectory(directory());
    Map<String, String> files = new TreeMap<>();
    for (String path : source.paths()) {
      if (!path.equals(PackSource.CHECKSUMS) && !path.equals(PackSource.ATTESTATION)) {
        files.put(path, source.text(path));
      }
    }
    return files;
  }

  /** The fixture with the given files replaced (a null text removes the file), freshly sealed. */
  static PackSource sealed(Map<String, String> overrides) {
    Map<String, String> files = files();
    overrides.forEach(
        (path, text) -> {
          if (text == null) {
            files.remove(path);
          } else {
            files.put(path, text);
          }
        });
    Map<String, byte[]> bytes = new HashMap<>();
    files.forEach((path, text) -> bytes.put(path, text.getBytes(UTF_8)));
    bytes.put(PackSource.CHECKSUMS, Checksums.render(PackSource.of(bytes)).getBytes(UTF_8));
    return PackSource.of(bytes);
  }

  static LoadedPack load() {
    return loadSealed(Map.of());
  }

  /** Loads a sealed variant; fails the test on any load problem. */
  static LoadedPack loadSealed(Map<String, String> overrides) {
    LoadResult result = PackLoader.load(sealed(overrides));
    List<String> problems = new ArrayList<>();
    for (PackProblem p : result.problems()) {
      problems.add(p.code() + " " + p.path() + ": " + p.message());
    }
    assertThat(problems).as("problems of the fixture variant").isEmpty();
    return result.pack().orElseThrow();
  }

  /** The text of a fixture file with every occurrence of {@code from} replaced. */
  static String replaced(String path, String from, String to) {
    String text = files().get(path);
    assertThat(text).as(path).contains(from);
    return text.replace(from, to);
  }

  /** A new execution of the fixture: BUY, AOTC, a client LEI, a counterparty, an algorithm. */
  static CanonicalExecution newExecution() {
    return execution(LifecycleKind.NEW, new BigDecimal("12.25"), Optional.of("ALGO-1"));
  }

  static CanonicalExecution execution(
      LifecycleKind kind, BigDecimal price, Optional<String> executionWithin) {
    return new CanonicalExecution(
        "LEI-FIRM-0001",
        "XPAR",
        "EX-1",
        kind,
        Instant.parse("2026-10-30T09:30:00.123456Z"),
        AssetClass.EQUITY,
        "ZZ0000000016",
        true,
        PartyKind.LEGAL_ENTITY,
        Optional.of("LEI-CLIENT-0001"),
        new BigDecimal("100.50000"),
        price,
        "EUR",
        Optional.of(Side.BUY),
        Optional.of("AOTC"),
        Optional.of("LEI-CCP-0001"),
        executionWithin);
  }

  /** The fixture execution without its optional counterparty. */
  static CanonicalExecution withoutCounterparty() {
    CanonicalExecution e = newExecution();
    return new CanonicalExecution(
        e.firmLei(),
        e.venueMic(),
        e.venueExecutionId(),
        e.eventKind(),
        e.tradedAt(),
        e.assetClass(),
        e.isin(),
        e.tradedOnVenue(),
        e.clientKind(),
        e.clientLei(),
        e.quantity(),
        e.price(),
        e.currency(),
        e.side(),
        e.capacity(),
        Optional.empty(),
        e.executionWithinFirm());
  }
}
