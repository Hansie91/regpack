package dev.regpack.spikes.x.xml;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.model.Sha256;
import dev.regpack.spikes.x.TestDocuments;
import dev.regpack.spikes.x.TestSchemas;
import dev.regpack.spikes.x.schema.SchemaStats;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

class XmlRecordGenTest {
  /** The digest of the document of 100 records of seed 1, pinned like the corpus digests of T2. */
  private static final String DOCUMENT_SEED_1_100_RECORDS =
      "ec63ac8eb6ccfa4e679c359c49812b391bb8537f08ace5975c2ffc8165fc8c84";

  private static int occurrence(String segment) {
    int hash = segment.indexOf('#');
    return hash < 0 ? 1 : Integer.parseInt(segment.substring(hash + 1));
  }

  private static String stripped(String path) {
    return path.replaceAll("#[0-9]+", "");
  }

  @Test
  void recordHas65LeafFieldsInSchemaOrder() throws IOException {
    List<String> schemaOrder = SchemaStats.leafPaths(TestSchemas.TXN);
    assertThat(XmlRecordGen.fieldPaths()).hasSize(65).isEqualTo(schemaOrder);
    for (TxModel record : TestDocuments.records(3, 300)) {
      assertThat(record.names()).isNotEmpty();
      int previous = -1;
      Map<String, Integer> lastOccurrence = new HashMap<>();
      for (String name : record.names()) {
        int index = schemaOrder.indexOf(stripped(name));
        assertThat(index).as("path %s is a leaf of the schema", name).isNotNegative();
        // only the first occurrence of a repeating group walks forward through the schema
        boolean firstOccurrence =
            java.util.Arrays.stream(name.split("/")).allMatch(s -> occurrence(s) == 1);
        if (firstOccurrence) {
          assertThat(index).as("%s comes after the previous leaf", name).isGreaterThan(previous);
          previous = index;
        }
        // occurrences of a group are numbered 1, 2, 3 without a gap
        for (String segment : name.split("/")) {
          int hash = segment.indexOf('#');
          if (hash >= 0) {
            String group = name.substring(0, name.indexOf(segment) + hash);
            int number = occurrence(segment);
            int last = lastOccurrence.getOrDefault(group, 0);
            assertThat(number).isIn(last, last + 1);
            lastOccurrence.put(group, number);
          }
        }
      }
    }
  }

  @Test
  void everyLeafOccursAndBothChoiceAlternativesAreExercised() {
    Set<String> seen = new HashSet<>();
    for (TxModel record : TestDocuments.records(5, 3_000)) {
      for (String name : record.names()) {
        seen.add(stripped(name));
      }
    }
    assertThat(seen)
        .as("a leaf that never occurs is a facet that is never validated")
        .containsExactlyInAnyOrderElementsOf(XmlRecordGen.fieldPaths());
  }

  @Test
  void thousandRecordsValidateAgainstTheSchema() throws SAXException, IOException {
    Schema schema = TestSchemas.txn();
    byte[] document = TestDocuments.document(TestDocuments.records(1, 1_000));
    schema.newValidator().validate(new StreamSource(new ByteArrayInputStream(document)));
  }

  @Test
  void sameSeedSameBytes() {
    byte[] first = TestDocuments.document(TestDocuments.records(1, 100));
    byte[] second = TestDocuments.document(TestDocuments.records(1, 100));
    byte[] other = TestDocuments.document(TestDocuments.records(2, 100));
    assertThat(first).isEqualTo(second);
    assertThat(other).isNotEqualTo(first);
    assertThat(Sha256.of(first).hex()).isEqualTo(DOCUMENT_SEED_1_100_RECORDS);
  }

  @Test
  void groupOccurrencesMatchTheTargets() {
    int records = 10_000;
    Map<String, Long> total = new HashMap<>();
    Map<String, Integer> most = new HashMap<>();
    for (TxModel record : TestDocuments.records(7, records)) {
      Map<String, Set<String>> instances = new HashMap<>();
      for (String name : record.names()) {
        String[] segments = name.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
          if (segments[i].indexOf('#') >= 0) {
            String group = segments[i].substring(0, segments[i].indexOf('#'));
            instances.computeIfAbsent(group, g -> new HashSet<>()).add(segments[i]);
          }
        }
      }
      for (Map.Entry<String, Set<String>> e : instances.entrySet()) {
        total.merge(e.getKey(), (long) e.getValue().size(), Long::sum);
        most.merge(e.getKey(), e.getValue().size(), Math::max);
      }
    }
    assertThat(total.keySet()).containsExactlyInAnyOrder("alias", "underlying");
    for (String group : total.keySet()) {
      double average = total.get(group) / (double) records;
      assertThat(average).as("average occurrences of %s per record", group).isBetween(2.0, 6.0);
      assertThat(most.get(group)).as("most occurrences of %s", group).isLessThanOrEqualTo(20);
    }
  }

  @Test
  void averageRecordIsAboutTwoPointEightKilobytes() {
    int records = 2_000;
    byte[] document = TestDocuments.document(TestDocuments.records(11, records));
    byte[] empty = TestDocuments.document(List.of());
    double perRecord = (document.length - empty.length) / (double) records;
    assertThat(perRecord).as("bytes per serialised record").isBetween(2_600.0, 3_000.0);
  }
}
