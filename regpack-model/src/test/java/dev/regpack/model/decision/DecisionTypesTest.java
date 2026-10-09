package dev.regpack.model.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Citation;
import dev.regpack.model.CitationRole;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.canonical.AssetClass;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.model.json.Json;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DecisionTypesTest {
  private static DecisionContext context(String engineBuild) {
    return new DecisionContext(
        Sha256.ofUtf8("pack"),
        Optional.empty(),
        List.of("pin-1"),
        List.of("ref-1"),
        List.of(),
        "fp-1",
        "map-1",
        engineBuild,
        "interpreter");
  }

  private static DecisionBody body(
      Instant evaluatedAt, Outcome outcome, long costUnits, List<String> flags) {
    return new DecisionBody(
        EventIdentity.of("L", "XPAR", "EX-1"),
        new Regime("mifir.eu.rts22"),
        context("b1").id(),
        Instant.parse("2026-10-09T09:30:00.000000Z"),
        evaluatedAt,
        outcome,
        flags,
        new ReasonCode("MIFIR.N03.EXCLUDED"),
        "n03",
        List.of("r1"),
        List.of(new PathStep("r1", true, costUnits)),
        List.of(
            new Citation("mifir", "art26", CitationRole.BASIS, Optional.of(Sha256.ofUtf8("q")))),
        List.of(Leg.MARKET),
        Sha256.ofUtf8("in"));
  }

  private static CanonicalExecution execution() {
    return new CanonicalExecution(
        "L",
        "XPAR",
        "EX-1",
        LifecycleKind.NEW,
        Instant.parse("2026-10-09T09:30:00.123456Z"),
        AssetClass.EQUITY,
        "FR0000120271",
        true,
        PartyKind.LEGAL_ENTITY,
        Optional.empty(),
        new BigDecimal("100.5"),
        new BigDecimal("12.25"),
        "EUR");
  }

  @Test
  void contextIdIsSha256OfCanonicalJson() {
    assertThat(context("b1").id()).isEqualTo(context("b1").id());
    assertThat(context("b1").id()).isNotEqualTo(context("b2").id());
    assertThat(context("b1").id())
        .isEqualTo(Sha256.ofUtf8(Json.canonical(DecisionCodec.contextToMap(context("b1")))));
  }

  @Test
  void replayDigestIgnoresEvaluatedAtOnly() {
    Instant t1 = Instant.parse("2026-10-09T10:00:00.000000Z");
    Instant t2 = Instant.parse("2026-10-09T11:00:00.000000Z");
    DecisionBody base = body(t1, Outcome.REPORT, 1, List.of());
    assertThat(body(t2, Outcome.REPORT, 1, List.of()).replayDigest())
        .isEqualTo(base.replayDigest());
    assertThat(body(t1, Outcome.DO_NOT_REPORT, 1, List.of()).replayDigest())
        .isNotEqualTo(base.replayDigest());
    assertThat(body(t1, Outcome.REPORT, 2, List.of()).replayDigest())
        .isNotEqualTo(base.replayDigest());
    assertThat(DecisionCodec.bodyToMap(base, true)).containsKey("evaluatedAt");
    assertThat(DecisionCodec.bodyToMap(base, false)).doesNotContainKey("evaluatedAt");
  }

  @Test
  void inputDigestChangesWithSourceVersion() {
    CanonicalExecution e = execution();
    Sha256 v1 = InputDigest.of(e, new IngestKey("src", "rec-1", 1));
    Sha256 v3 = InputDigest.of(e, new IngestKey("src", "rec-1", 3));
    assertThat(v1).isNotEqualTo(v3);
    assertThat(InputDigest.of(e, new IngestKey("src", "rec-1", 1))).isEqualTo(v1);
  }

  @Test
  void bodyListsAreImmutable() {
    List<String> flags = new ArrayList<>(List.of("a"));
    DecisionBody b = body(Instant.parse("2026-10-09T10:00:00.000000Z"), Outcome.REPORT, 1, flags);
    flags.add("b");
    assertThat(b.flags()).containsExactly("a");
    assertThatThrownBy(() -> b.flags().add("x")).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> b.path().add(new PathStep("x", true, 0)))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
