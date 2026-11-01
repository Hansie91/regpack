package dev.regpack.flow.ingest;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.model.Sha256;
import dev.regpack.ports.connector.SourceConnector.ReadLimits;
import dev.regpack.testkit.clock.ManualClock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;

/** Test helper: the four folders of a file source in a temporary directory, and files to drop. */
final class InboxFixture {
  static final String HEADER =
      "record_id,version,event,exec_id,venue_mic,traded_at,isin,on_venue,account,client_lei,"
          + "client_type,side,capacity,quantity,price,currency,counterparty,exec_within\n";

  final Path inbox;
  final Path processed;
  final Path rejected;
  final Path quarantine;
  final ManualClock clock = new ManualClock(Instant.parse("2026-10-31T12:00:00Z"));

  InboxFixture(Path root) {
    this.inbox = dir(root, "inbox");
    this.processed = dir(root, "processed");
    this.rejected = dir(root, "rejected");
    this.quarantine = dir(root, "quarantine");
  }

  private static Path dir(Path root, String name) {
    try {
      return Files.createDirectories(root.resolve(name));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  FileSourceConnector connector(long maxBytes) {
    return new FileSourceConnector(
        inbox, processed, rejected, quarantine, clock, "trades", new ReadLimits(100, maxBytes));
  }

  FileSourceConnector connector() {
    return connector(1_048_576L);
  }

  /** One data row for the given execution id; the quantity is 10. */
  static String row(String recordId, String execId) {
    return recordId
        + ",1,NEW,"
        + execId
        + ",XPAR,2026-10-30T09:30:00.123456Z,ZZ0000000016,true,ACC-1,SYNT00CLIENT00000167,"
        + "LEGAL_ENTITY,BUY,AOTC,10,12.25,EUR,SYNT00CCP00000000194,ALGO-1\n";
  }

  /** A CSV of one row. */
  static byte[] oneRow(String recordId, String execId) {
    return (HEADER + row(recordId, execId)).getBytes(UTF_8);
  }

  static String markerFor(String name, byte[] csv, int records) {
    return "{\"data_class\":\"SYNTHETIC\",\"file\":\""
        + name
        + "\",\"records\":"
        + records
        + ",\"sha256\":\""
        + Sha256.of(csv).hex()
        + "\",\"size\":"
        + csv.length
        + ",\"sum_quantity\":\""
        + (10 * records)
        + "\"}\n";
  }

  /**
   * The marker of {@code name} (one record) followed by blanks up to exactly {@code total} bytes.
   */
  static byte[] paddedMarkerFor(String name, byte[] csv, int total) {
    byte[] marker = markerFor(name, csv, 1).getBytes(UTF_8);
    if (marker.length > total) {
      throw new IllegalArgumentException("the marker is longer than the size asked for");
    }
    byte[] padded = Arrays.copyOf(marker, total);
    Arrays.fill(padded, marker.length, total, (byte) ' ');
    return padded;
  }

  /** Drops a CSV and the given marker bytes (the marker last). */
  Path dropWithMarker(String name, byte[] csv, byte[] marker) {
    Path file = write(name, csv);
    write(name + ".done", marker);
    return file;
  }

  /** Drops a CSV and its marker (the marker last, as a producer would). */
  Path drop(String name, byte[] csv) {
    Path file = write(name, csv);
    write(name + ".done", markerFor(name, csv, 1).getBytes(UTF_8));
    return file;
  }

  Path dropWithoutMarker(String name, byte[] csv) {
    return write(name, csv);
  }

  Path write(String name, byte[] bytes) {
    try {
      return Files.write(inbox.resolve(name), bytes);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  boolean exists(Path dir, String name) {
    return Files.exists(dir.resolve(name));
  }
}
