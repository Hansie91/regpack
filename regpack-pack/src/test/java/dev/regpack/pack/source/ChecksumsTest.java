package dev.regpack.pack.source;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.Sha256;
import dev.regpack.pack.check.PackProblem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** CHECKSUMS: the integrity file of a pack (companion contracts section 2). */
class ChecksumsTest {
  private static final String H_A = Sha256.ofUtf8("alpha\n").hex();

  private static PackSource source(Map<String, String> files) {
    Map<String, byte[]> bytes = new LinkedHashMap<>();
    files.forEach((k, v) -> bytes.put(k, v.getBytes(UTF_8)));
    return PackSource.of(bytes);
  }

  private static Map<String, String> files(String... pathsAndContents) {
    Map<String, String> m = new LinkedHashMap<>();
    for (int i = 0; i < pathsAndContents.length; i += 2) {
      m.put(pathsAndContents[i], pathsAndContents[i + 1]);
    }
    return m;
  }

  private static PackSource withChecksums(Map<String, String> files, String checksums) {
    Map<String, String> all = new LinkedHashMap<>(files);
    all.put("CHECKSUMS", checksums);
    return source(all);
  }

  private static List<String> codes(List<PackProblem> problems) {
    return problems.stream().map(PackProblem::code).toList();
  }

  @Test
  void renderIsSortedAndLfNormalised() {
    PackSource src =
        source(files("scope/c.yaml", "c\n", "b.yaml", "x\r\ny\r\n", "a.yaml", "alpha\n"));

    String expected =
        H_A
            + "  a.yaml\n"
            + Sha256.ofUtf8("x\ny\n").hex()
            + "  b.yaml\n"
            + Sha256.ofUtf8("c\n").hex()
            + "  scope/c.yaml\n";
    assertThat(Checksums.render(src)).isEqualTo(expected);
  }

  @Test
  void verifyAcceptsRenderedChecksums() {
    Map<String, String> f = files("a.yaml", "alpha\n", "scope/c.yaml", "c: 1\n");
    String checksums = Checksums.render(source(f));
    assertThat(Checksums.verify(withChecksums(f, checksums))).isEmpty();
  }

  @Test
  void aChangedByteIsAMismatch() {
    Map<String, String> f = files("a.yaml", "alpha\n", "b.yaml", "beta\n");
    String checksums = Checksums.render(source(f));
    Map<String, String> changed = files("a.yaml", "alpha\n", "b.yaml", "betA\n");

    List<PackProblem> problems = Checksums.verify(withChecksums(changed, checksums));
    assertThat(codes(problems)).containsExactly("CHECKSUM_MISMATCH");
    assertThat(problems.get(0).path()).isEqualTo("b.yaml");
  }

  @Test
  void anUnlistedFileIsAProblem() {
    Map<String, String> f = files("a.yaml", "alpha\n");
    String checksums = Checksums.render(source(f));
    Map<String, String> more = files("a.yaml", "alpha\n", "extra.yaml", "x\n");

    List<PackProblem> problems = Checksums.verify(withChecksums(more, checksums));
    assertThat(codes(problems)).containsExactly("CHECKSUM_UNLISTED_FILE");
    assertThat(problems.get(0).path()).isEqualTo("extra.yaml");
  }

  @Test
  void aMissingFileIsAProblem() {
    Map<String, String> f = files("a.yaml", "alpha\n", "b.yaml", "beta\n");
    String checksums = Checksums.render(source(f));

    List<PackProblem> problems =
        Checksums.verify(withChecksums(files("a.yaml", "alpha\n"), checksums));
    assertThat(codes(problems)).containsExactly("CHECKSUM_FILE_MISSING");
    assertThat(problems.get(0).path()).isEqualTo("b.yaml");
  }

  @Test
  void aDuplicateEntryIsAProblem() {
    Map<String, String> f = files("a.yaml", "alpha\n");
    String line = H_A + "  a.yaml\n";

    List<PackProblem> problems = Checksums.verify(withChecksums(f, line + line));
    assertThat(codes(problems)).containsExactly("CHECKSUMS_DUPLICATE_ENTRY");
    assertThat(problems.get(0).path()).isEqualTo("CHECKSUMS");
    assertThat(problems.get(0).line()).isEqualTo(2);
  }

  @Test
  void aCrlfCheckoutVerifiesLikeLf() {
    Map<String, String> lf = files("a.yaml", "one\ntwo\n", "scope/b.yaml", "k: v\n");
    String checksums = Checksums.render(source(lf));
    Map<String, String> crlf = files("a.yaml", "one\r\ntwo\r\n", "scope/b.yaml", "k: v\r\n");

    assertThat(Checksums.render(source(crlf))).isEqualTo(checksums);
    // a Windows checkout may also convert the CHECKSUMS file itself
    assertThat(Checksums.verify(withChecksums(crlf, checksums.replace("\n", "\r\n")))).isEmpty();
  }

  @Test
  void attestationAndChecksumsAreNotListed() {
    Map<String, String> f = files("a.yaml", "alpha\n", "ATTESTATION", "opaque bytes");
    String rendered = Checksums.render(withChecksums(f, "anything\n"));
    assertThat(rendered).isEqualTo(H_A + "  a.yaml\n");
    assertThat(Checksums.verify(withChecksums(f, rendered))).isEmpty();

    // listing either of them is itself a problem
    String bad = rendered + Sha256.ofUtf8("x").hex() + "  ATTESTATION\n";
    assertThat(codes(Checksums.verify(withChecksums(f, bad))))
        .containsExactly("CHECKSUMS_MALFORMED");
  }

  @Test
  void missingChecksumsFileIsNamed() {
    List<PackProblem> problems = Checksums.verify(source(files("a.yaml", "alpha\n")));
    assertThat(codes(problems)).containsExactly("CHECKSUMS_MISSING");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // 63 hex digits
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde  a.yaml\n",
        // upper-case hex
        "0123456789ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef  a.yaml\n",
        // one space
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef a.yaml\n",
        // three spaces
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef   a.yaml\n",
        // a tab
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\ta.yaml\n",
        // no path
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef  \n",
        // an empty line in the middle
        "\n0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef  a.yaml\n",
        // a path that is not a valid pack path
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef  ../a.yaml\n",
        "just text\n"
      })
  void malformedLinesAreRejected(String checksums) {
    Map<String, String> f = files("a.yaml", "alpha\n");
    List<PackProblem> problems = Checksums.verify(withChecksums(f, checksums));
    assertThat(codes(problems)).contains("CHECKSUMS_MALFORMED");
    assertThat(problems.stream().filter(p -> p.code().equals("CHECKSUMS_MALFORMED")).findFirst())
        .get()
        .satisfies(p -> assertThat(p.path()).isEqualTo("CHECKSUMS"));
  }

  @Test
  void problemsAreSorted() {
    Map<String, String> f = files("a.yaml", "alpha\n", "z.yaml", "zeta\n");
    String stale = Checksums.render(source(files("a.yaml", "ALPHA\n", "m.yaml", "mu\n")));
    List<PackProblem> problems = Checksums.verify(withChecksums(f, stale));
    assertThat(problems).isSorted();
    assertThat(codes(problems))
        .containsExactly("CHECKSUM_MISMATCH", "CHECKSUM_FILE_MISSING", "CHECKSUM_UNLISTED_FILE");
  }
}
