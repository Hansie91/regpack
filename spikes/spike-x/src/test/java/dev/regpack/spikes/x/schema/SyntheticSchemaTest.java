package dev.regpack.spikes.x.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.spikes.x.TestSchemas;
import dev.regpack.tools.G3Check;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.SAXException;

/**
 * Review focus RF7 (no ESMA-looking content: every name is invented, the marker and the own
 * namespace are present) and RF9 (the stand-in has the declared weight, criterion X-6).
 */
class SyntheticSchemaTest {
  private static final Pattern INVENTED_NAME = Pattern.compile("^[a-z][a-z0-9_]{2,}$");
  private static final String MARKER =
      "<!-- SELF-AUTHORED SYNTHETIC SCHEMA: not derived from any ESMA or ISO 20022 artefact"
          + " (gate G3) -->";

  @Test
  void shapeStatisticsMatchTheDeclaredTargets() throws IOException {
    SchemaStats.Stats stats = SchemaStats.of(TestSchemas.TXN);
    assertThat(stats.outsideTargets()).as("statistics outside the declared targets").isEmpty();
    assertThat(stats.leafElementsUnderTx()).isEqualTo(65);
    assertThat(stats.maxDepth()).isEqualTo(5);
    assertThat(stats.choices()).isEqualTo(3);
    assertThat(stats.unboundedGroups()).isEqualTo(2);
    assertThat(stats.digitTypes()).isEqualTo(6);
    assertThat(stats.lengthTypes()).isEqualTo(6);
    assertThat(stats.attributes()).isEqualTo(6);
    assertThat(stats.namedComplexTypes()).isGreaterThanOrEqualTo(8);
    assertThat(stats.enumerationTypes()).isGreaterThanOrEqualTo(12);
    assertThat(stats.patternTypes()).isGreaterThanOrEqualTo(8);
    assertThat(stats.optionalShare()).isBetween(new BigDecimal("0.35"), new BigDecimal("0.55"));
  }

  @Test
  void costDriversMatchTheDeclaredTargets() throws IOException {
    SchemaStats.Stats stats = SchemaStats.of(TestSchemas.TXN);
    assertThat(stats.minEnumerationSize())
        .as("every enumeration has at least 4 values")
        .isGreaterThanOrEqualTo(4);
    assertThat(stats.maxEnumerationSize()).isGreaterThanOrEqualTo(250);
    assertThat(stats.enumerationsOf180OrMore())
        .as("one with 180 and one with 250")
        .isGreaterThanOrEqualTo(2);
    assertThat(stats.patternAlternations()).isGreaterThanOrEqualTo(3);
    assertThat(stats.maxRepetitionBound()).isGreaterThanOrEqualTo(20);
    // the fraction digits of the widest decimal: 18 digits in total, 17 after the point
    String text = Files.readString(TestSchemas.TXN, StandardCharsets.UTF_8);
    assertThat(text).contains("<xs:totalDigits value=\"18\"/><xs:fractionDigits value=\"17\"/>");
  }

  @Test
  void outsideTargetsNamesTheBrokenOnes() {
    SchemaStats.Stats good = statsWith(65, 3, new BigDecimal("0.452"));
    assertThat(good.outsideTargets()).isEmpty();
    assertThat(statsWith(64, 3, new BigDecimal("0.452")).outsideTargets())
        .containsExactly("leafElementsUnderTx");
    assertThat(statsWith(65, 4, new BigDecimal("0.452")).outsideTargets())
        .containsExactly("choices");
    assertThat(statsWith(65, 3, new BigDecimal("0.560")).outsideTargets())
        .containsExactly("optionalShare");
    assertThat(statsWith(65, 3, new BigDecimal("0.349")).outsideTargets())
        .containsExactly("optionalShare");
  }

  private static SchemaStats.Stats statsWith(int leaves, int choices, BigDecimal optionalShare) {
    return new SchemaStats.Stats(
        leaves, 5, 8, 12, 8, 6, 6, choices, 2, optionalShare, 6, 250, 3, 4, 2, 20);
  }

  @Test
  void statisticsOfASmallKnownSchemaAreExact(@TempDir Path dir) throws IOException {
    // 3 leaves and 1 container below the record, depth 3, one choice, one unbounded element,
    // one optional element of four declarations, one attribute, one enumeration of 4 values.
    String xsd =
        """
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
          <xs:simpleType name="four_values">
            <xs:restriction base="xs:string">
              <xs:enumeration value="a"/><xs:enumeration value="b"/>
              <xs:enumeration value="c"/><xs:enumeration value="d"/>
            </xs:restriction>
          </xs:simpleType>
          <xs:simpleType name="coded">
            <xs:restriction base="xs:string"><xs:pattern value="(x|y|z)[0-9]{7}"/></xs:restriction>
          </xs:simpleType>
          <xs:complexType name="tagged"><xs:simpleContent>
            <xs:extension base="xs:string"><xs:attribute name="mark" type="xs:string"/></xs:extension>
          </xs:simpleContent></xs:complexType>
          <xs:element name="transaction_record"><xs:complexType><xs:sequence>
            <xs:choice>
              <xs:element name="one" type="four_values"/>
              <xs:element name="two" type="coded"/>
            </xs:choice>
            <xs:element name="inner" maxOccurs="unbounded"><xs:complexType><xs:sequence>
              <xs:element name="three" type="tagged" minOccurs="0"/>
            </xs:sequence></xs:complexType></xs:element>
          </xs:sequence></xs:complexType></xs:element>
        </xs:schema>
        """;
    Path file = dir.resolve("small.xsd");
    Files.writeString(file, xsd, StandardCharsets.UTF_8);
    SchemaStats.Stats stats = SchemaStats.of(file);
    assertThat(stats.leafElementsUnderTx()).isEqualTo(3);
    assertThat(stats.maxDepth()).isEqualTo(3);
    assertThat(stats.choices()).isEqualTo(1);
    assertThat(stats.unboundedGroups()).isEqualTo(1);
    assertThat(stats.optionalShare()).isEqualByComparingTo("0.250");
    assertThat(stats.attributes()).isEqualTo(1);
    assertThat(stats.namedComplexTypes()).isEqualTo(1);
    assertThat(stats.enumerationTypes()).isEqualTo(1);
    assertThat(stats.minEnumerationSize()).isEqualTo(4);
    assertThat(stats.patternTypes()).isEqualTo(1);
    assertThat(stats.patternAlternations()).isEqualTo(2);
    assertThat(stats.maxRepetitionBound()).isEqualTo(7);
    assertThat(SchemaStats.leafPaths(file)).containsExactly("one", "two", "inner/three");
  }

  @Test
  void namesAreInventedAndMarkerIsPresent() throws IOException {
    for (Path xsd : List.of(TestSchemas.TXN, TestSchemas.PROBE)) {
      assertThat(SchemaStats.declaredNames(xsd))
          .as("declared names of %s", xsd.getFileName())
          .isNotEmpty()
          .allSatisfy(name -> assertThat(name).matches(INVENTED_NAME));
      String text = Files.readString(xsd, StandardCharsets.UTF_8);
      assertThat(text.lines().findFirst()).contains(MARKER);
      assertThat(text).contains("targetNamespace=\"urn:regpack:spike:synthetic:");
      // the build gate itself, on the real file paths of the repository
      String relative = "spikes/spike-x/src/main/resources/synthetic/" + xsd.getFileName();
      assertThat(G3Check.check(Path.of("..", ".."), List.of(relative))).isEmpty();
    }
    assertThat(SchemaStats.declaredNames(TestSchemas.TXN))
        .contains("transaction_record", "f01_record_ref", "f65_comment");
  }

  @Test
  void everyLeafIsNumberedInOrderFromOneToSixtyFive() throws IOException {
    List<String> paths = SchemaStats.leafPaths(TestSchemas.TXN);
    assertThat(paths).hasSize(65);
    for (int i = 0; i < paths.size(); i++) {
      String path = paths.get(i);
      String leaf = path.substring(path.lastIndexOf('/') + 1);
      String number = i + 1 < 10 ? "0" + (i + 1) : Integer.toString(i + 1);
      assertThat(leaf).as("leaf %d", i + 1).startsWith("f" + number + "_");
    }
  }

  @Test
  void schemaCompilesUnderTheJdkValidator() throws SAXException, IOException {
    assertThat(TestSchemas.factory().getProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA))
        .as("no external access")
        .isEqualTo("");
    assertThat(TestSchemas.txn()).isNotNull();
  }

  @Test
  void xsd11ConstructIsRejectedByTheJdk() {
    assertThatThrownBy(() -> TestSchemas.compile(TestSchemas.PROBE))
        .as("the JDK validator implements XSD 1.0 only (criterion X-5)")
        .isInstanceOf(SAXException.class);
  }
}
