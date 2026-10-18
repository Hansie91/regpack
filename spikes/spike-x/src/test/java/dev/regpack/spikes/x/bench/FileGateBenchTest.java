package dev.regpack.spikes.x.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.Sha256;
import dev.regpack.spikes.x.TestSchemas;
import dev.regpack.spikes.x.validate.DefectInjector.Kind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.validation.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.SAXException;

class FileGateBenchTest {
  private static int count(String text, String needle) {
    int count = 0;
    for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
      count++;
    }
    return count;
  }

  @Test
  void fileOfTheRequestedRecordCountValidates(@TempDir Path dir) throws IOException, SAXException {
    Schema schema = TestSchemas.txn();
    Path file = FileGateBench.generate(dir, 2_000, 5L);
    assertThat(file).exists().startsWith(dir);
    String text = Files.readString(file, StandardCharsets.UTF_8);
    assertThat(count(text, "<transaction_record>")).isEqualTo(2_000);
    assertThat(text).startsWith("<?xml");
    assertThat(FileGateBench.validateNanos(file, schema))
        .as("gate time in nanoseconds")
        .isPositive();

    // the same seed writes the same bytes; another seed does not
    Path again = FileGateBench.generate(Files.createDirectory(dir.resolve("again")), 2_000, 5L);
    Path other = FileGateBench.generate(Files.createDirectory(dir.resolve("other")), 2_000, 6L);
    assertThat(Sha256.of(Files.readAllBytes(again))).isEqualTo(Sha256.of(Files.readAllBytes(file)));
    assertThat(Sha256.of(Files.readAllBytes(other)))
        .isNotEqualTo(Sha256.of(Files.readAllBytes(file)));
  }

  @Test
  void aSeededDefectInTheMiddleFailsTheGate(@TempDir Path dir) throws IOException, SAXException {
    Schema schema = TestSchemas.txn();
    int records = 600;
    assertThat(FileGateBench.validateNanos(FileGateBench.generate(dir, records, 8L), schema))
        .as("the same file without a defect passes")
        .isPositive();
    for (Kind kind : Kind.values()) {
      Path dirOfKind = Files.createDirectory(dir.resolve(kind.name()));
      Path file = FileGateBench.generate(dirOfKind, records, 8L, records / 2, kind);
      assertThat(count(Files.readString(file, StandardCharsets.UTF_8), "<transaction_record>"))
          .as("a defect of kind %s keeps the file well-formed and the record in place", kind)
          .isEqualTo(records);
      assertThatThrownBy(() -> FileGateBench.validateNanos(file, schema))
          .as("%s in the middle of the file", kind)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("cvc-");
    }
  }

  /**
   * Found by the first full-size run: with secure processing on, the JDK parser stops a document
   * that holds more than 100,000 characters of entity references ({@code &amp;} and the like), and
   * a file of 100,000 records holds about that many. The gate lifts that one limit; the others stay
   * (external access, expansion and total-size limits).
   */
  @Test
  void aFileWithMoreThanOneHundredThousandEntityReferencesPassesTheGate(@TempDir Path dir)
      throws IOException, SAXException {
    String xsd =
        "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">"
            + "<xs:element name=\"t\" type=\"xs:string\"/></xs:schema>";
    Schema schema =
        TestSchemas.factory()
            .newSchema(new javax.xml.transform.stream.StreamSource(new java.io.StringReader(xsd)));
    Path file = dir.resolve("entities.xml");
    Files.writeString(file, "<t>" + "&amp;".repeat(100_500) + "</t>", StandardCharsets.UTF_8);
    assertThat(FileGateBench.validateNanos(file, schema)).isPositive();
  }

  @Test
  void validatorsUseSecureProcessingAndNoExternalAccess(@TempDir Path dir)
      throws IOException, SAXException {
    // an otherwise valid file that points at an external DTD: the access property forbids the
    // fetch, so the gate fails on the restriction and never tries to connect
    Schema schema = TestSchemas.txn();
    String text = Files.readString(FileGateBench.generate(dir, 3, 1L), StandardCharsets.UTF_8);
    int afterDeclaration = text.indexOf("?>") + 2;
    Path file = dir.resolve("external.xml");
    Files.writeString(
        file,
        text.substring(0, afterDeclaration)
            + "<!DOCTYPE transaction_file SYSTEM \"http://127.0.0.1:9/x.dtd\">"
            + text.substring(afterDeclaration),
        StandardCharsets.UTF_8);
    assertThatThrownBy(() -> FileGateBench.validateNanos(file, schema))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("accessExternalDTD");
  }
}
