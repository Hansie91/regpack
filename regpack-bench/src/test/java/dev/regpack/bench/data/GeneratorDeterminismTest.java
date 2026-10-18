package dev.regpack.bench.data;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.Sha256;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pins below are checked on every operating system of the build matrix (review focus RF2): the
 * suite runs under a hostile default locale and time zone, so a pin that holds there holds
 * anywhere.
 */
class GeneratorDeterminismTest {
  private static final String LONGS_SEED_42 =
      "8cd750e9ee8a755d58b3b60d6a4c748d1196aa001b44203ae0547e5fef98d11a";
  private static final String TRADE_CORPUS_SEED_1 =
      "350df8c7010ff6406236863a396b084e2548a1b2780447df70b957b124541cbd";
  private static final String RULE_PACK_SEED_1_S1_200 =
      "d68cdc670579a504192aac7df46c8d04414a8f1a47cd036064b6996739906d1e";

  @Test
  void digestOfTenThousandValuesOfSeed42IsPinned() {
    SplitMix64 rng = new SplitMix64(42);
    long[] values = new long[10_000];
    for (int i = 0; i < values.length; i++) {
      values[i] = rng.nextLong();
    }
    assertThat(Digests.ofLongs(values).hex()).isEqualTo(LONGS_SEED_42);
  }

  @Test
  void tradeCorpusDigestIsPinned() {
    TradeRecordGen gen = new TradeRecordGen(1);
    List<String> lines = new ArrayList<>();
    for (int i = 0; i < 1_000; i++) {
      lines.add(gen.next().toNdjsonLine());
    }
    assertThat(Digests.ofLines(lines).hex()).isEqualTo(TRADE_CORPUS_SEED_1);
  }

  @Test
  void rulePackDigestIsPinned() {
    GeneratedPack pack = RulePackGen.generate(1, 200, PackShape.S1_FIRST_HIT_REALISTIC);
    assertThat(pack.digest().hex()).isEqualTo(RULE_PACK_SEED_1_S1_200);
  }

  @Test
  void digestsAreDefinedByTheirByteLayout() {
    // 8 big-endian bytes per long; each line in UTF-8 followed by one LF.
    assertThat(Digests.ofLongs(new long[] {1L}))
        .isEqualTo(Sha256.of(new byte[] {0, 0, 0, 0, 0, 0, 0, 1}));
    assertThat(Digests.ofLines(List.of("a", "é")))
        .isEqualTo(Sha256.of(new byte[] {'a', '\n', (byte) 0xC3, (byte) 0xA9, '\n'}));
    assertThat(Digests.ofLines(List.of())).isEqualTo(Sha256.of(new byte[0]));
  }

  @Test
  void sameSeedTwiceGivesTheSameCorpusAndADifferentSeedDoesNot() {
    TradeRecordGen a = new TradeRecordGen(77);
    TradeRecordGen b = new TradeRecordGen(77);
    TradeRecordGen c = new TradeRecordGen(78);
    boolean differs = false;
    for (int i = 0; i < 200; i++) {
      String line = a.next().toNdjsonLine();
      assertThat(b.next().toNdjsonLine()).isEqualTo(line);
      differs |= !c.next().toNdjsonLine().equals(line);
    }
    assertThat(differs).isTrue();
  }
}
