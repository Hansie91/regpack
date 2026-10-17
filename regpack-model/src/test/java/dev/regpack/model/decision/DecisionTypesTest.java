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
import dev.regpack.model.canonical.CanonicalExecutionCodec;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.canonical.PartyKind;
import dev.regpack.model.json.Json;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

  private static DecisionBody variant(
      DecisionBody base,
      Instant asAt,
      List<String> flags,
      List<Citation> citations,
      Sha256 inputDigest) {
    return new DecisionBody(
        base.eventIdentity(),
        base.regime(),
        base.decisionContextId(),
        asAt,
        base.evaluatedAt(),
        base.outcome(),
        flags,
        base.reasonCode(),
        base.node(),
        base.ruleIdsHit(),
        base.path(),
        citations,
        base.legs(),
        inputDigest);
  }

  @Test
  void replayBodyIsTheFullBodyMinusEvaluatedAt() {
    DecisionBody base =
        body(Instant.parse("2026-10-09T10:00:00.000000Z"), Outcome.REPORT, 1, List.of("f1"));
    Map<String, Object> withoutEvaluatedAt =
        new LinkedHashMap<>(DecisionCodec.bodyToMap(base, true));
    assertThat(withoutEvaluatedAt.remove("evaluatedAt")).isEqualTo(base.evaluatedAt());
    assertThat(withoutEvaluatedAt).isEqualTo(DecisionCodec.bodyToMap(base, false));
    assertThat(DecisionCodec.bodyToMap(base, false).keySet())
        .containsExactlyElementsOf(withoutEvaluatedAt.keySet());
  }

  @Test
  void replayDigestCoversEveryFieldExceptEvaluatedAt() {
    DecisionBody base =
        body(Instant.parse("2026-10-09T10:00:00.000000Z"), Outcome.REPORT, 1, List.of("f1"));
    Sha256 expected = base.replayDigest();
    List<Citation> citations = base.citations();
    Citation other = new Citation("mifir", "art26", CitationRole.EXCEPTION, Optional.empty());
    assertThat(
            variant(base, base.asAt(), List.of("f1"), citations, base.inputDigest()).replayDigest())
        .isEqualTo(expected);
    assertThat(
            variant(base, base.asAt(), List.of("f2"), citations, base.inputDigest()).replayDigest())
        .as("flags")
        .isNotEqualTo(expected);
    assertThat(variant(base, base.asAt(), List.of(), citations, base.inputDigest()).replayDigest())
        .as("no flags")
        .isNotEqualTo(expected);
    assertThat(
            variant(base, base.asAt().plusSeconds(1), base.flags(), citations, base.inputDigest())
                .replayDigest())
        .as("asAt")
        .isNotEqualTo(expected);
    assertThat(
            variant(base, base.asAt(), base.flags(), citations, Sha256.ofUtf8("other"))
                .replayDigest())
        .as("inputDigest")
        .isNotEqualTo(expected);
    assertThat(
            variant(base, base.asAt(), base.flags(), List.of(other), base.inputDigest())
                .replayDigest())
        .as("citation")
        .isNotEqualTo(expected);
    Citation otherRole =
        new Citation("mifir", "art26", CitationRole.EXCEPTION, Optional.of(Sha256.ofUtf8("q")));
    assertThat(
            variant(base, base.asAt(), base.flags(), List.of(otherRole), base.inputDigest())
                .replayDigest())
        .as("citation role")
        .isNotEqualTo(expected);
  }

  @Test
  void inputDigestMatchesTheGoldenVector() {
    CanonicalExecution e = execution();
    String json =
        "{\"assetClass\":\"EQUITY\",\"clientKind\":\"LEGAL_ENTITY\",\"currency\":\"EUR\","
            + "\"eventKind\":\"NEW\",\"firmLei\":\"L\",\"isin\":\"FR0000120271\",\"price\":12.25,"
            + "\"quantity\":100.5,\"tradedAt\":\"2026-10-09T09:30:00.123456Z\","
            + "\"tradedOnVenue\":true,\"venueExecutionId\":\"EX-1\",\"venueMic\":\"XPAR\"}";
    assertThat(Json.canonical(CanonicalExecutionCodec.toMap(e))).isEqualTo(json);
    Sha256 digest = InputDigest.of(e, new IngestKey("src", "rec-1", 1));
    assertThat(digest).isEqualTo(Sha256.ofUtf8(json + "\nsrc|rec-1|1"));
    assertThat(digest.hex())
        .isEqualTo("30ddc5cf01393b67d6c92facf300b21058c9c95392f5d5882b147582c5fe1e7d");
    assertThat(InputDigest.of(e, new IngestKey("src2", "rec-1", 1))).isNotEqualTo(digest);
    assertThat(InputDigest.of(e, new IngestKey("src", "rec-2", 1))).isNotEqualTo(digest);
    assertThat(InputDigest.of(e, new IngestKey("src", "rec-1", 2))).isNotEqualTo(digest);
  }

  @Test
  void contextIdMatchesTheGoldenVector() {
    String json =
        "{\"engineBuild\":\"b1\",\"enrichmentSnapshotIds\":[],\"executor\":\"interpreter\","
            + "\"firmProfileVersion\":\"fp-1\",\"interpretationPins\":[\"pin-1\"],"
            + "\"mappingVersion\":\"map-1\",\"packDigest\":\""
            + Sha256.ofUtf8("pack").hex()
            + "\",\"referenceSnapshotIds\":[\"ref-1\"]}";
    assertThat(Json.canonical(DecisionCodec.contextToMap(context("b1")))).isEqualTo(json);
    assertThat(context("b1").id().hex())
        .isEqualTo("2c82bc6e73b568970a7152dcfd075c866a01dec5bc6c74d707e950e790a7be84");
  }

  // The two literals below were printed from the unchanged 14-component model (plan T15 step 1a).
  private static final String PRE_ATTRIBUTES_JSON =
      "{\"asAt\":\"2026-10-09T09:30:00.000000Z\",\"citations\":[{\"provisionPath\":\"art26\","
          + "\"quoteSha256\":\"8e35c2cd3bf6641bdb0e2050b76932cbb2e6034a0ddacc1d9bea82a6ba57f7cf\","
          + "\"role\":\"BASIS\",\"sourceId\":\"mifir\"}],\"decisionContextId\":\""
          + "2c82bc6e73b568970a7152dcfd075c866a01dec5bc6c74d707e950e790a7be84\","
          + "\"evaluatedAt\":\"2026-10-09T10:00:00.000000Z\",\"eventIdentity\":\"L|XPAR|EX-1\","
          + "\"flags\":[],\"inputDigest\":\""
          + "582967534d0f909d196b97f9e6921342777aea87b46fa52df165389db1fb8ccf\","
          + "\"legs\":[\"MARKET\"],\"node\":\"n03\",\"outcome\":\"REPORT\","
          + "\"path\":[{\"costUnits\":1,\"matched\":true,\"ruleId\":\"r1\"}],"
          + "\"reasonCode\":\"MIFIR.N03.EXCLUDED\",\"regime\":\"mifir.eu.rts22\","
          + "\"ruleIdsHit\":[\"r1\"]}";
  private static final String PRE_ATTRIBUTES_REPLAY_DIGEST =
      "a6188c06f1e6afacb809c22f9fb4067d660f65652411ce8598728725c3f69cbe";

  private static DecisionBody withAttributes(DecisionBody base, Map<String, String> attributes) {
    return new DecisionBody(
        base.eventIdentity(),
        base.regime(),
        base.decisionContextId(),
        base.asAt(),
        base.evaluatedAt(),
        base.outcome(),
        base.flags(),
        base.reasonCode(),
        base.node(),
        base.ruleIdsHit(),
        base.path(),
        base.citations(),
        base.legs(),
        base.inputDigest(),
        attributes);
  }

  @Test
  void oldConstructorGivesEmptyAttributes() {
    DecisionBody b =
        body(Instant.parse("2026-10-09T10:00:00.000000Z"), Outcome.REPORT, 1, List.of());
    assertThat(b.attributes()).isEmpty();
  }

  @Test
  void emptyAttributesLeaveEveryExistingDigestUnchanged() {
    DecisionBody b =
        body(Instant.parse("2026-10-09T10:00:00.000000Z"), Outcome.REPORT, 1, List.of());
    assertThat(Json.canonical(DecisionCodec.bodyToMap(b, true))).isEqualTo(PRE_ATTRIBUTES_JSON);
    assertThat(b.replayDigest().hex()).isEqualTo(PRE_ATTRIBUTES_REPLAY_DIGEST);
    assertThat(DecisionCodec.bodyToMap(b, true)).doesNotContainKey("attributes");
    assertThat(DecisionCodec.bodyToMap(withAttributes(b, Map.of()), true))
        .isEqualTo(DecisionCodec.bodyToMap(b, true));
  }

  @Test
  void attributesAreInTheReplayDigest() {
    Instant t1 = Instant.parse("2026-10-09T10:00:00.000000Z");
    Instant t2 = Instant.parse("2026-10-09T11:00:00.000000Z");
    DecisionBody base = body(t1, Outcome.REPORT, 1, List.of());
    DecisionBody with = withAttributes(base, Map.of("action", "NEWT"));
    assertThat(with.replayDigest()).isNotEqualTo(base.replayDigest());
    assertThat(
            withAttributes(body(t2, Outcome.REPORT, 1, List.of()), Map.of("action", "NEWT"))
                .replayDigest())
        .isEqualTo(with.replayDigest());
    assertThat(withAttributes(base, Map.of("action", "CANC")).replayDigest())
        .isNotEqualTo(with.replayDigest());
  }

  @Test
  void attributesAppearSortedInTheCanonicalJson() {
    DecisionBody base =
        body(Instant.parse("2026-10-09T10:00:00.000000Z"), Outcome.REPORT, 1, List.of());
    Map<String, String> unsorted = new LinkedHashMap<>();
    unsorted.put("zeta", "1");
    unsorted.put("action", "NEWT");
    DecisionBody with = withAttributes(base, unsorted);
    assertThat(Json.canonical(DecisionCodec.bodyToMap(with, true)))
        .contains("\"attributes\":{\"action\":\"NEWT\",\"zeta\":\"1\"}");
    assertThat(with.attributes().keySet()).containsExactly("action", "zeta");
    assertThat(DecisionCodec.bodyToMap(with, true).get("attributes"))
        .isEqualTo(Map.of("action", "NEWT", "zeta", "1"));
  }

  @Test
  void attributesAreCopiedAndImmutable() {
    DecisionBody base =
        body(Instant.parse("2026-10-09T10:00:00.000000Z"), Outcome.REPORT, 1, List.of());
    Map<String, String> source = new LinkedHashMap<>();
    source.put("action", "NEWT");
    DecisionBody with = withAttributes(base, source);
    source.put("later", "x");
    assertThat(with.attributes()).containsOnlyKeys("action");
    assertThatThrownBy(() -> with.attributes().put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> withAttributes(base, null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void attributesRejectNullKeysAndValuesAtConstruction() {
    DecisionBody base =
        body(Instant.parse("2026-10-09T10:00:00.000000Z"), Outcome.REPORT, 1, List.of());
    Map<String, String> nullValue = new LinkedHashMap<>();
    nullValue.put("action", null);
    assertThatThrownBy(() -> withAttributes(base, nullValue))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("attribute value");
    Map<String, String> nullKey = new LinkedHashMap<>();
    nullKey.put(null, "NEWT");
    assertThatThrownBy(() -> withAttributes(base, nullKey))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("attribute key");
    assertThatThrownBy(() -> withAttributes(base, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("attributes");
  }

  @Test
  void pathStepRejectsBlankRuleIdAndNegativeCost() {
    assertThatThrownBy(() -> new PathStep("", true, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PathStep("  ", true, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PathStep("r", true, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new PathStep("r", false, 0).costUnits()).isZero();
  }
}
