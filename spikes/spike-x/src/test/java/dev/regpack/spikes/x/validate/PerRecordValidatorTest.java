package dev.regpack.spikes.x.validate;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.spikes.x.TestDocuments;
import dev.regpack.spikes.x.TestSchemas;
import dev.regpack.spikes.x.validate.DefectInjector.Kind;
import dev.regpack.spikes.x.xml.TxModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.xml.validation.Schema;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

class PerRecordValidatorTest {
  /**
   * The JDK message key that each kind of defect must produce first, so a defect is not an
   * accident.
   */
  private static final Map<Kind, String> FIRST_ERROR =
      Map.of(
          Kind.PATTERN, "cvc-pattern-valid",
          Kind.ENUMERATION, "cvc-enumeration-valid",
          Kind.OVER_LENGTH, "cvc-maxLength-valid",
          Kind.TOO_MANY_DIGITS, "cvc-totalDigits-valid|cvc-fractionDigits-valid",
          Kind.MISSING_MANDATORY, "cvc-complex-type.2.4",
          Kind.WRONG_ORDER, "cvc-complex-type.2.4");

  @Test
  void acceptsAValidRecordAndRejectsEachDefectKind() throws SAXException, IOException {
    Schema schema = TestSchemas.txn();
    PerRecordValidator validator = new PerRecordValidator(schema);
    for (TxModel model : TestDocuments.records(31, 120)) {
      byte[] good = DefectInjector.serialise(model);
      assertThat(validator.validate(validator.wrap(good))).as("a valid record").isZero();
      for (Kind kind : Kind.values()) {
        for (int variant = 0; variant < 3; variant++) {
          byte[] bad = DefectInjector.serialise(DefectInjector.corrupt(model, kind, variant));
          int errors = validator.validate(validator.wrap(bad));
          assertThat(errors).as("%s variant %d is rejected", kind, variant).isPositive();
          assertThat(validator.firstError())
              .as("%s variant %d is rejected for its own reason", kind, variant)
              .matches("(?s)(" + FIRST_ERROR.get(kind) + ").*");
        }
      }
    }
  }

  @Test
  void aRecordThatIsNotWellFormedCountsAsAnErrorInsteadOfThrowing()
      throws SAXException, IOException {
    PerRecordValidator validator = new PerRecordValidator(TestSchemas.txn());
    byte[] broken =
        validator.wrap(
            "<transaction_record><oops></transaction_record>"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThat(validator.validate(broken)).isPositive();
    assertThat(
            validator.validate(
                validator.wrap(DefectInjector.serialise(TestDocuments.records(32, 1).get(0)))))
        .isZero();
  }

  @Test
  void validatorIsReusedAfterResetWithoutLeakingState() throws SAXException, IOException {
    PerRecordValidator validator = new PerRecordValidator(TestSchemas.txn());
    List<TxModel> records = TestDocuments.records(33, 6);
    byte[] good = validator.wrap(DefectInjector.serialise(records.get(0)));
    byte[] bad =
        validator.wrap(
            DefectInjector.serialise(DefectInjector.corrupt(records.get(1), Kind.PATTERN, 0)));
    byte[] worse =
        validator.wrap(
            DefectInjector.serialise(
                DefectInjector.corrupt(records.get(2), Kind.MISSING_MANDATORY, 0)));

    int badErrors = validator.validate(bad);
    int worseErrors = validator.validate(worse);
    assertThat(badErrors).isPositive();
    assertThat(worseErrors).isPositive();
    for (int round = 0; round < 25; round++) {
      assertThat(validator.validate(good)).as("good after bad, round %d", round).isZero();
      assertThat(validator.firstError()).as("no message leaks from the last record").isEmpty();
      assertThat(validator.validate(bad)).as("the count does not accumulate").isEqualTo(badErrors);
      assertThat(validator.validate(worse)).isEqualTo(worseErrors);
    }
  }

  @Test
  void wrapperKeepsTheRecordBytesAndIsValidAroundAnyRecord() throws SAXException, IOException {
    PerRecordValidator validator = new PerRecordValidator(TestSchemas.txn());
    byte[] record = DefectInjector.serialise(TestDocuments.records(34, 1).get(0));
    byte[] wrapped = validator.wrap(record);
    assertThat(wrapped.length).isGreaterThan(record.length);
    // a slice of a larger buffer wraps to the same bytes as the record on its own
    byte[] padded = new byte[record.length + 10];
    System.arraycopy(record, 0, padded, 4, record.length);
    assertThat(validator.wrap(padded, 4, record.length)).isEqualTo(wrapped);
    String text = new String(wrapped, java.nio.charset.StandardCharsets.UTF_8);
    assertThat(text).contains(new String(record, java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  void threadConfinedValidatorsRunInParallel() throws Exception {
    Schema schema = TestSchemas.txn();
    int threads = 4;
    int perThread = 4_000;
    List<List<byte[]>> work = new ArrayList<>();
    List<Integer> expectedBad = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      List<TxModel> models = TestDocuments.records(40 + t, 200);
      List<byte[]> bytes = new ArrayList<>(perThread);
      int bad = 0;
      for (int i = 0; i < perThread; i++) {
        TxModel model = models.get(i % models.size());
        if (i % 10 == 3) {
          model = DefectInjector.corrupt(model, Kind.values()[(i / 10) % 6], i);
          bad++;
        }
        bytes.add(DefectInjector.serialise(model));
      }
      work.add(bytes);
      expectedBad.add(bad);
    }
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        List<byte[]> mine = work.get(t);
        futures.add(
            pool.submit(
                () -> {
                  PerRecordValidator validator = new PerRecordValidator(schema);
                  int rejected = 0;
                  for (byte[] record : mine) {
                    if (validator.validate(validator.wrap(record)) > 0) {
                      rejected++;
                    }
                  }
                  return rejected;
                }));
      }
      for (int t = 0; t < threads; t++) {
        assertThat(futures.get(t).get())
            .as("rejected by thread %d", t)
            .isEqualTo(expectedBad.get(t));
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
