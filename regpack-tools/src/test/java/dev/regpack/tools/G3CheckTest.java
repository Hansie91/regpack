package dev.regpack.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class G3CheckTest {
  /** Built by concatenation so that this file never trips the guard it tests. */
  private static final String ISO_NAMESPACE = "urn:" + "iso:std:iso:" + "20022:tech:xsd:";

  private static final String ESMA_HOST = "esma." + "europa.eu";
  private static final String ISO_HOST = "iso" + "20022.org";
  private static final String MARKER =
      "<!-- SELF-AUTHORED SYNTHETIC SCHEMA: not derived from any ESMA or ISO 20022 artefact"
          + " (gate G3) -->";
  private static final String SYNTHETIC_DIR = "spikes/spike-x/src/main/resources/synthetic/";
  private static final String SYNTHETIC_TEST_DIR = "spikes/spike-x/src/test/resources/synthetic/";

  @TempDir Path root;

  private String write(String relative, String content) throws IOException {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return relative;
  }

  private static String schema(String namespace, boolean marker) {
    return "<?xml version=\"1.0\"?>\n"
        + (marker ? MARKER + "\n" : "")
        + "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" targetNamespace=\""
        + namespace
        + "\"/>\n";
  }

  @Test
  void flagsTheIso20022NamespaceInAnySourceFile() throws IOException {
    String java = write("regpack-x/src/A.java", "String s = \"" + ISO_NAMESPACE + "auth.016\";\n");
    String json = write("pack/p.json", "{\"ns\":\"" + ISO_NAMESPACE + "head.001\"}\n");
    String clean = write("regpack-x/src/B.java", "class B {}\n");
    assertThat(G3Check.check(root, List.of(java, json, clean)))
        .containsExactly("G3_NAMESPACE " + java, "G3_NAMESPACE " + json);
  }

  @Test
  void flagsAnXsdOutsideTheSyntheticDirectories() throws IOException {
    String other = write("spikes/spike-x/src/main/resources/real/a.xsd", schema("x", true));
    String upper = write("regpack-model/src/main/resources/A.XSD", schema("x", true));
    assertThat(G3Check.check(root, List.of(other, upper)))
        .containsExactly("G3_XSD_PATH " + other, "G3_XSD_PATH " + upper);
  }

  @Test
  void flagsAnXsdUnderDocs() throws IOException {
    String doc = write("docs/spikes/sample.xsd", schema("urn:regpack:spike:synthetic:a", true));
    assertThat(G3Check.check(root, List.of(doc))).containsExactly("G3_XSD_PATH " + doc);
  }

  @Test
  void acceptsASyntheticSchemaWithMarkerAndOwnNamespace() throws IOException {
    String main =
        write(SYNTHETIC_DIR + "trade.xsd", schema("urn:regpack:spike:synthetic:trade:1", true));
    String test =
        write(
            SYNTHETIC_TEST_DIR + "nested/small.xsd",
            schema("urn:regpack:spike:synthetic:small", true));
    assertThat(G3Check.check(root, List.of(main, test))).isEmpty();
  }

  @Test
  void rejectsASyntheticSchemaWithoutTheMarker() throws IOException {
    String noMarker =
        write(SYNTHETIC_DIR + "a.xsd", schema("urn:regpack:spike:synthetic:a", false));
    String foreignNamespace = write(SYNTHETIC_DIR + "b.xsd", schema("urn:example:other", true));
    String noNamespace = write(SYNTHETIC_DIR + "c.xsd", "<xs:schema>" + MARKER + "</xs:schema>\n");
    assertThat(G3Check.check(root, List.of(noMarker, foreignNamespace, noNamespace)))
        .containsExactly(
            "G3_SYNTHETIC_MARKER " + noMarker,
            "G3_SYNTHETIC_MARKER " + foreignNamespace,
            "G3_SYNTHETIC_MARKER " + noNamespace);
  }

  @Test
  void flagsAnEsmaSchemaLocation() throws IOException {
    String esma =
        write(
            "spikes/x.xml",
            "<a xsi:schemaLocation=\"urn:x https://www." + ESMA_HOST + "/s/auth.xsd\"/>\n");
    String iso =
        write("regpack-x/t.xml", "<a schemaLocation = 'https://www." + ISO_HOST + "/a.xsd'/>\n");
    String harmless = write("regpack-x/u.xml", "<a schemaLocation=\"local.xsd\"/>\n");
    assertThat(G3Check.check(root, List.of(esma, iso, harmless)))
        .containsExactly("G3_SCHEMA_LOCATION " + esma, "G3_SCHEMA_LOCATION " + iso);
  }

  @Test
  void acceptsProseUnderDocsThatQuotesANamespace() throws IOException {
    String prose =
        write(
            "docs/research/note.md",
            "The namespace `"
                + ISO_NAMESPACE
                + "auth.016` and <a schemaLocation=\"https://"
                + ESMA_HOST
                + "/x.xsd\"/> are quoted here.\n");
    assertThat(G3Check.check(root, List.of(prose))).isEmpty();
  }

  @Test
  void flagsWorkbookFilesAndArchives() throws IOException {
    String xlsx = write("docs/research/rules.xlsx", "x");
    String xlsm = write("a/b.XLSM", "x");
    String xls = write("a/c.xls", "x");
    String csv = write("data/x-workbook.csv", "a,b\n");
    String zip = write("vendor/bundle.zip", "x");
    String allowed = write("docs/spikes/workbook-notes.md", "Notes about a workbook.\n");
    assertThat(G3Check.check(root, List.of(xlsx, xlsm, xls, csv, zip, allowed)))
        .containsExactly(
            "G3_WORKBOOK " + xlsx,
            "G3_WORKBOOK " + xlsm,
            "G3_WORKBOOK " + xls,
            "G3_WORKBOOK " + csv,
            "G3_ARCHIVE " + zip);
  }

  @Test
  void flagsByNameEvenWhenTheFileIsNotOnDisk() throws IOException {
    String gone = "gone/file.xsd";
    String zip = write("z.zip", "x");
    assertThat(G3Check.check(root, List.of(gone, zip)))
        .containsExactly("G3_XSD_PATH " + gone, "G3_ARCHIVE " + zip);
  }

  @Test
  void passesOnTheRepositoryAsCommitted() throws IOException, InterruptedException {
    Path repo = Path.of(System.getProperty("regpack.repo.root"));
    assertThat(G3Check.check(repo, trackedAndUntracked(repo))).isEmpty();
  }

  private static List<String> trackedAndUntracked(Path repo)
      throws IOException, InterruptedException {
    List<String> paths = new ArrayList<>();
    for (String[] args :
        List.of(
            new String[] {"git", "-C", repo.toString(), "ls-files"},
            new String[] {"git", "-C", repo.toString(), "ls-files", "-o", "--exclude-standard"})) {
      Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(process.waitFor()).isZero();
      output.lines().filter(line -> !line.isBlank()).forEach(paths::add);
    }
    return paths;
  }
}
