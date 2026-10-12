package dev.regpack.spikes.r.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.bench.data.GeneratedPack;
import dev.regpack.bench.data.PackShape;
import dev.regpack.bench.data.RulePackGen;
import dev.regpack.ports.exec.CostBudget;
import dev.regpack.spikes.r.bench.HotSwap.Detail;
import dev.regpack.spikes.r.bench.HotSwap.Pinning;
import dev.regpack.spikes.r.bench.HotSwap.Run;
import dev.regpack.spikes.r.inhouse.InHouseExpression;
import dev.regpack.spikes.r.table.CompiledPack;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class HotSwapTest {
  private static final InHouseExpression LAYER = new InHouseExpression();

  private static GeneratedPack pack(long seed, int rules) {
    return RulePackGen.generate(seed, rules, PackShape.S2_FULL_SCAN_WORST_CASE);
  }

  private static KeyPair keys() throws GeneralSecurityException {
    return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  }

  private static byte[] sign(KeyPair keys, byte[] data) throws GeneralSecurityException {
    Signature s = Signature.getInstance("Ed25519");
    s.initSign(keys.getPrivate());
    s.update(data);
    return s.sign();
  }

  private static byte[] ir(GeneratedPack pack) {
    return pack.irJson().getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void noBatchSeesTwoPacks() {
    Run run = HotSwap.measure(LAYER, pack(1L, 40), pack(2L, 40), 2, 20);
    assertThat(run.mixedPackBatches()).isZero();
    assertThat(run.lostRecords()).isZero();
    assertThat(run.activationNanos()).hasSize(20);
  }

  @Test
  void swapInvariantsHoldUnderConcurrentLoad() {
    Detail detail =
        HotSwap.measureDetailed(
            LAYER, pack(1L, 40), pack(2L, 40), 2, 20, Pinning.PER_BATCH, HotSwap.BATCH_SIZE);
    assertThat(detail.submitted()).as("something was evaluated").isPositive();
    assertThat(detail.evaluated()).as("submitted equals evaluated").isEqualTo(detail.submitted());
    assertThat(detail.run().lostRecords()).isZero();
    assertThat(detail.run().mixedPackBatches()).isZero();
    assertThat(detail.generationsEvaluated())
        .as("the evaluators saw the initial pack and every swapped-in pack")
        .isEqualTo(21);
    assertThat(java.util.Arrays.stream(detail.run().activationNanos()).allMatch(n -> n > 0))
        .isTrue();
  }

  @Test
  void recordsThatAreNotDecidedAreCountedAsLost() {
    // fault injection: the evaluator skips every 10th record of the ring position it was handed
    AtomicLong calls = new AtomicLong();
    HotSwap.Decider skipping =
        (executor, program, record) ->
            calls.incrementAndGet() % 10 == 0
                ? Optional.empty()
                : Optional.of(executor.execute(program, record, new CostBudget(Long.MAX_VALUE)));
    Detail detail =
        HotSwap.measureDetailed(
            LAYER, pack(1L, 20), pack(2L, 20), 2, 5, Pinning.PER_BATCH, 100, skipping);
    assertThat(detail.submitted()).isPositive();
    assertThat(detail.run().lostRecords()).as("skipped records are lost").isPositive();
    assertThat(detail.run().lostRecords()).isEqualTo(detail.submitted() - detail.evaluated());
    assertThat(detail.evaluated()).isLessThan(detail.submitted());
  }

  @Test
  void everyKindOfDecisionCountsAsEvaluated() {
    // a failed or budget-exhausted record is still decided (one decision per record), not lost
    CostBudget tiny = new CostBudget(1L);
    HotSwap.Decider exhausting =
        (executor, program, record) -> Optional.of(executor.execute(program, record, tiny));
    Detail detail =
        HotSwap.measureDetailed(
            LAYER, pack(1L, 20), pack(2L, 20), 2, 5, Pinning.PER_BATCH, 100, exhausting);
    assertThat(detail.submitted()).isPositive();
    assertThat(detail.run().lostRecords()).isZero();
    assertThat(detail.evaluated()).isEqualTo(detail.submitted());
  }

  @Test
  void aBatchThatRepinsPerRecordIsDetectedAsMixed() {
    // a huge batch so that the swaps certainly fall inside a batch: the audit has teeth
    Detail detail =
        HotSwap.measureDetailed(
            LAYER, pack(1L, 20), pack(2L, 20), 2, 20, Pinning.PER_RECORD, 200_000);
    assertThat(detail.run().mixedPackBatches()).isPositive();
  }

  @Test
  void batchAuditFlagsAMixedBatchAndOnlyThat() {
    HotSwap.BatchAudit audit = new HotSwap.BatchAudit();
    audit.record("A");
    audit.record("A");
    assertThat(audit.endBatch()).as("one pack").isFalse();
    audit.record("A");
    audit.record("B");
    assertThat(audit.endBatch()).as("two packs").isTrue();
    assertThat(audit.endBatch()).as("an empty batch").isFalse();
    audit.record("B");
    assertThat(audit.endBatch()).as("the audit starts afresh").isFalse();
  }

  @Test
  void signatureIsVerifiedBeforeActivation() throws GeneralSecurityException {
    KeyPair keys = keys();
    GeneratedPack initial = pack(1L, 20);
    GeneratedPack next = pack(2L, 20);
    PackHolder holder = new PackHolder(CompiledPack.compile(initial, LAYER));
    PackHolder.Active before = holder.current();
    byte[] bytes = ir(next);
    byte[] signature = sign(keys, bytes);

    byte[] flipped = bytes.clone();
    // the first priority 100 becomes 101: the tampered IR still parses and still compiles, so
    // only the signature can refuse it
    int at = new String(flipped, StandardCharsets.UTF_8).indexOf("\"priority\":100") + 13;
    flipped[at] = '1';
    assertThat(holder.activate(flipped, signature, keys.getPublic(), LAYER)).isFalse();
    assertThat(holder.current()).as("a flipped IR byte").isSameAs(before);

    byte[] badSignature = signature.clone();
    badSignature[0] ^= 1;
    assertThat(holder.activate(bytes, badSignature, keys.getPublic(), LAYER)).isFalse();
    assertThat(holder.current()).as("a flipped signature byte").isSameAs(before);

    assertThat(holder.activate(bytes, signature, keys().getPublic(), LAYER))
        .as("another key")
        .isFalse();
    assertThat(holder.current()).isSameAs(before);

    assertThat(holder.activate(bytes, signature, keys.getPublic(), LAYER)).isTrue();
    assertThat(holder.current()).isNotSameAs(before);
    assertThat(holder.current().generation()).isEqualTo(before.generation() + 1);
    assertThat(holder.current().pack().digest()).isEqualTo(next.digest());
  }

  @Test
  void aSignedButBrokenPackIsNeverActivated() throws GeneralSecurityException {
    KeyPair keys = keys();
    PackHolder holder = new PackHolder(CompiledPack.compile(pack(1L, 20), LAYER));
    PackHolder.Active before = holder.current();

    byte[] notJson = "not a pack".getBytes(StandardCharsets.UTF_8);
    assertThat(holder.activate(notJson, sign(keys, notJson), keys.getPublic(), LAYER)).isFalse();

    byte[] noCatchAll =
        ("[[{\"id\":\"A\",\"table\":1,\"priority\":100,\"when\":\"event.kind == 'NEW'\","
                + "\"outcome\":\"REPORT\",\"reason\":\"A\"}]]")
            .getBytes(StandardCharsets.UTF_8);
    assertThat(holder.activate(noCatchAll, sign(keys, noCatchAll), keys.getPublic(), LAYER))
        .as("the pack check refuses a table without its catch-all")
        .isFalse();
    assertThat(holder.current()).isSameAs(before);
  }

  @Test
  void parsedIrRoundTripsTheGeneratedPack() {
    GeneratedPack source = pack(3L, 40);
    GeneratedPack parsed = PackHolder.parseIr(ir(source));
    assertThat(parsed.irJson()).isEqualTo(source.irJson());
    assertThat(parsed.ruleCount()).isEqualTo(40);
    assertThatThrownBy(() -> PackHolder.parseIr("{}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
