package dev.regpack.spikes.x.bench;

import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.spikes.x.validate.DefectInjector;
import dev.regpack.spikes.x.validate.DefectInjector.Kind;
import dev.regpack.spikes.x.xml.StaxRecordWriter;
import dev.regpack.spikes.x.xml.TxModel;
import dev.regpack.spikes.x.xml.XmlRecordGen;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.Validator;
import org.xml.sax.SAXException;

/**
 * The whole-file gate (the L6 gate of the architecture): one streaming JDK {@link Validator} over a
 * file. {@link #generate} writes the file that is validated; it is not part of the measurement.
 */
public final class FileGateBench {
  private static final String MESSAGE_ID = "file-gate";
  private static final String GENERAL_ENTITY_LIMIT = "jdk.xml.maxGeneralEntitySizeLimit";
  private static final String TOTAL_ENTITY_LIMIT = "jdk.xml.totalEntitySizeLimit";

  private FileGateBench() {}

  /** A file of {@code records} generated records of the seed, in {@code dir}; returns its path. */
  public static Path generate(Path dir, int records, long seed) {
    // no defect: the index is out of range and the kind is never used
    return write(
        dir.resolve("gate-" + records + "-" + seed + ".xml"), records, seed, -1, Kind.PATTERN);
  }

  /**
   * The same file with one defective record of the kind at index {@code defectIndex}. The record is
   * still in its place, so the file is well-formed and only the schema rejects it.
   */
  public static Path generate(Path dir, int records, long seed, int defectIndex, Kind kind) {
    Objects.requireNonNull(kind, "kind");
    if (defectIndex < 0 || defectIndex >= records) {
      throw new IllegalArgumentException(
          "defectIndex must be from 0 to " + (records - 1) + ": " + defectIndex);
    }
    return write(
        dir.resolve("gate-" + records + "-" + seed + "-" + kind + "-" + defectIndex + ".xml"),
        records,
        seed,
        defectIndex,
        kind);
  }

  private static Path write(Path file, int records, long seed, int defectIndex, Kind kind) {
    if (records < 0) {
      throw new IllegalArgumentException("records must not be negative: " + records);
    }
    TradeRecordGen trades = new TradeRecordGen(seed);
    XmlRecordGen gen = new XmlRecordGen(seed);
    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 20)) {
      StaxRecordWriter writer = new StaxRecordWriter(out);
      writer.startDocument(MESSAGE_ID, 0L);
      for (int i = 0; i < records; i++) {
        TxModel model = gen.next(trades.next());
        writer.writeTx(i == defectIndex ? DefectInjector.corrupt(model, kind, i) : model);
      }
      writer.endDocument();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return file;
  }

  /**
   * A validator for a whole file: secure processing on, no external access, and the one JDK limit
   * that a file of generated size breaks lifted. With secure processing on the JDK parser stops a
   * document that holds more than 100,000 characters of entity references ({@code &amp;} and the
   * like in the text of the records), and a file of 100,000 records holds about that many; the
   * first full-size run found it. The limits on the size of general entities (one, and all
   * together) are switched off here; external access stays off and the entity expansion limit stays
   * on.
   */
  public static Validator newValidator(Schema schema) {
    Validator validator = schema.newValidator();
    try {
      validator.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      validator.setProperty(GENERAL_ENTITY_LIMIT, "0");
      validator.setProperty(TOTAL_ENTITY_LIMIT, "0");
    } catch (SAXException e) {
      throw new IllegalStateException(
          "the JDK validator cannot be configured: " + e.getMessage(), e);
    }
    return validator;
  }

  /**
   * Validates the file with one streaming validator ({@link #newValidator}) and returns the
   * nanoseconds of the validation alone.
   *
   * @throws IllegalStateException when the file is not valid; the message is the first error
   */
  public static long validateNanos(Path file, Schema schema) {
    Validator validator = newValidator(schema);
    try {
      StreamSource source = new StreamSource(file.toFile());
      long start = System.nanoTime();
      validator.validate(source);
      return System.nanoTime() - start;
    } catch (SAXException e) {
      throw new IllegalStateException(
          "the whole-file gate rejected " + file.getFileName() + ": " + e.getMessage(), e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
