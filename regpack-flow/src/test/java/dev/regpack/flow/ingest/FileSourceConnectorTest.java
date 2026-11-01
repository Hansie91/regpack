package dev.regpack.flow.ingest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.regpack.flow.ingest.FileIngest.IngestReport;
import dev.regpack.flow.ingest.FileSourceConnector.Reader;
import dev.regpack.flow.ingest.FileSourceConnector.Reason;
import dev.regpack.flow.ingest.FileSourceConnector.Remover;
import dev.regpack.flow.ingest.FileSourceConnector.Seams;
import dev.regpack.flow.ingest.FileSourceConnector.Waiting;
import dev.regpack.model.Checkpoint;
import dev.regpack.model.Sha256;
import dev.regpack.ports.connector.SourceConnector.Batch;
import dev.regpack.ports.connector.SourceConnector.BatchStream;
import dev.regpack.ports.connector.SourceConnector.ReadLimits;
import dev.regpack.ports.connector.SourceConnector.ReadRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FileSourceConnectorTest {
  @TempDir Path root;

  private InboxFixture fx;

  @BeforeEach
  void setUp() {
    fx = new InboxFixture(root);
  }

  private static ReadRequest fromStart() {
    return new ReadRequest(Optional.empty(), new ReadLimits(100, 1_000_000L));
  }

  private static List<Batch> readAll(FileSourceConnector connector) {
    try (BatchStream stream = connector.read(fromStart())) {
      return stream.next(100);
    }
  }

  private static List<String> names(Path dir) throws IOException {
    try (Stream<Path> list = Files.list(dir)) {
      return list.map(p -> p.getFileName().toString()).sorted().toList();
    }
  }

  private static byte[] pad(byte[] csv, int size) {
    byte[] out = new byte[size];
    Arrays.fill(out, (byte) ' ');
    System.arraycopy(csv, 0, out, 0, Math.min(csv.length, size));
    return out;
  }

  @Test
  void aFileWithoutAMarkerWaitsAndIsNotRead() {
    fx.dropWithoutMarker("a.csv", InboxFixture.oneRow("T-1", "EX-1"));
    FileSourceConnector connector = fx.connector();
    assertThat(readAll(connector)).isEmpty();
    assertThat(connector.waiting())
        .containsExactly(
            new Waiting("a.csv", Instant.parse("2026-10-31T12:00:00Z"), Reason.NO_MARKER));
    assertThat(connector.bytesRead()).isZero();
    // the marker arrives: the file is a batch and no longer waits
    fx.write(
        "a.csv.done",
        InboxFixture.markerFor("a.csv", InboxFixture.oneRow("T-1", "EX-1"), 1).getBytes(UTF_8));
    fx.clock.advance(Duration.ofMinutes(5));
    List<Batch> batches = readAll(connector);
    assertThat(batches).hasSize(1);
    assertThat(connector.waiting()).isEmpty();
  }

  @Test
  void aWaitingFileKeepsTheInstantItWasFirstSeen() {
    fx.dropWithoutMarker("a.csv", InboxFixture.oneRow("T-1", "EX-1"));
    FileSourceConnector connector = fx.connector();
    readAll(connector);
    Instant first = connector.waiting().get(0).firstSeen();
    fx.clock.advance(Duration.ofHours(2));
    readAll(connector);
    assertThat(connector.waiting()).hasSize(1);
    assertThat(connector.waiting().get(0).firstSeen()).isEqualTo(first);
    // a file that leaves the inbox leaves the list
    try {
      Files.delete(fx.inbox.resolve("a.csv"));
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    readAll(connector);
    assertThat(connector.waiting()).isEmpty();
  }

  @Test
  void aMarkerWithAWrongHashWaits() {
    byte[] csv = InboxFixture.oneRow("T-1", "EX-1");
    fx.write("a.csv", csv);
    // the same size, the hash of other bytes
    String wrong = InboxFixture.markerFor("a.csv", InboxFixture.oneRow("T-9", "EX-9"), 1);
    assertThat(InboxFixture.oneRow("T-9", "EX-9")).hasSameSizeAs(csv);
    fx.write("a.csv.done", wrong.getBytes(UTF_8));
    FileSourceConnector connector = fx.connector();
    assertThat(readAll(connector)).isEmpty();
    assertThat(connector.waiting())
        .extracting(Waiting::reason)
        .containsExactly(Reason.MARKER_MISMATCH);
  }

  @Test
  void aMarkerWithAWrongSizeOrAnInvalidFormOrAnotherNameWaitsForTheRightReason() {
    byte[] csv = InboxFixture.oneRow("T-1", "EX-1");
    fx.write("size.csv", csv);
    fx.write(
        "size.csv.done",
        InboxFixture.markerFor("size.csv", csv, 1)
            .replace("\"size\":" + csv.length, "\"size\":" + (csv.length + 1))
            .getBytes(UTF_8));
    fx.write("form.csv", csv);
    fx.write("form.csv.done", "{\"file\":".getBytes(UTF_8));
    fx.write("other.csv", csv);
    fx.write("other.csv.done", InboxFixture.markerFor("form.csv", csv, 1).getBytes(UTF_8));
    fx.write("huge.csv", csv);
    // a valid marker of 4,097 bytes (blanks after it): its size is the only thing wrong
    fx.write("huge.csv.done", InboxFixture.paddedMarkerFor("huge.csv", csv, 4097));
    FileSourceConnector connector = fx.connector();
    assertThat(readAll(connector)).isEmpty();
    assertThat(connector.waiting())
        .extracting(Waiting::file, Waiting::reason)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("form.csv", Reason.MARKER_INVALID),
            org.assertj.core.groups.Tuple.tuple("huge.csv", Reason.MARKER_INVALID),
            org.assertj.core.groups.Tuple.tuple("other.csv", Reason.MARKER_INVALID),
            org.assertj.core.groups.Tuple.tuple("size.csv", Reason.MARKER_MISMATCH));
  }

  @Test
  void aFileOverTheLimitIsNeverRead() {
    byte[] big = pad(InboxFixture.oneRow("T-1", "EX-1"), 2048);
    fx.drop("big.csv", big);
    byte[] small = InboxFixture.oneRow("T-2", "EX-2");
    fx.drop("small.csv", small);
    FileSourceConnector connector = fx.connector(1024);
    List<Batch> batches = readAll(connector);
    // the next file is read; the big one is listed, untouched and unread
    assertThat(batches).extracting(Batch::sourceCoordinates).containsExactly("file:small.csv");
    assertThat(connector.waiting())
        .extracting(Waiting::file, Waiting::reason)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("big.csv", Reason.OVERSIZED));
    assertThat(connector.bytesRead()).isEqualTo(small.length);
    assertThat(fx.exists(fx.inbox, "big.csv")).isTrue();
    assertThat(fx.exists(fx.inbox, "big.csv.done")).isTrue();
  }

  @Test
  void theLimitIsTheSmallerOfTheConstructorAndTheRequest() {
    byte[] csv = pad(InboxFixture.oneRow("T-1", "EX-1"), 2048);
    fx.drop("a.csv", csv);
    FileSourceConnector connector = fx.connector(1_048_576L);
    try (BatchStream stream =
        connector.read(new ReadRequest(Optional.empty(), new ReadLimits(10, 1024L)))) {
      assertThat(stream.next(10)).isEmpty();
    }
    assertThat(connector.waiting()).extracting(Waiting::reason).containsExactly(Reason.OVERSIZED);
    assertThat(connector.bytesRead()).isZero();
    // a file of exactly the limit is read
    FileSourceConnector exact = fx.connector(2048);
    assertThat(readAll(exact)).hasSize(1);
  }

  @Test
  void aDirectoryOrAnOddNameWaitsWithItsReasonAndIsNotLost() throws IOException {
    Files.createDirectory(fx.inbox.resolve("dir.csv"));
    fx.drop("bad name.csv", InboxFixture.oneRow("T-1", "EX-1"));
    fx.drop(".hidden.csv", InboxFixture.oneRow("T-2", "EX-2"));
    fx.drop("good.csv", InboxFixture.oneRow("T-3", "EX-3"));
    FileSourceConnector connector = fx.connector();
    assertThat(readAll(connector))
        .extracting(Batch::sourceCoordinates)
        .containsExactly("file:good.csv");
    assertThat(connector.waiting())
        .extracting(Waiting::file, Waiting::reason)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(".hidden.csv", Reason.NAME_INVALID),
            org.assertj.core.groups.Tuple.tuple("bad name.csv", Reason.NAME_INVALID),
            org.assertj.core.groups.Tuple.tuple("dir.csv", Reason.NOT_REGULAR));
  }

  @Test
  void aBatchCarriesTheMarkerCountTheControlTotalsAndTheMediaType() {
    byte[] csv = InboxFixture.oneRow("T-1", "EX-1");
    fx.drop("a.csv", csv);
    Batch batch = readAll(fx.connector()).get(0);
    assertThat(batch.stream()).isEqualTo("trades");
    assertThat(batch.seq()).isEqualTo(1L);
    assertThat(batch.sourceCoordinates()).isEqualTo("file:a.csv");
    assertThat(batch.payload().mediaType()).isEqualTo("text/csv");
    assertThat(batch.payload().bytes()).isEqualTo(csv);
    assertThat(batch.payload().manifest().sha256()).isEqualTo(Sha256.of(csv));
    assertThat(batch.payload().manifest().recordCount()).isEqualTo(1);
    assertThat(batch.payload().manifest().controlTotals())
        .containsEntry("data_class", "SYNTHETIC")
        .containsEntry("sum_quantity", "10");
    assertThat(batch.checkpoint())
        .isEqualTo(new Checkpoint("trades", ByteBuffer.allocate(8).putLong(1L).array()));
  }

  @Test
  void theManifestCountIsTheCountTheMarkerDeclaresNotTheOneTheFileHas() {
    byte[] csv = InboxFixture.oneRow("T-1", "EX-1");
    fx.write("a.csv", csv);
    fx.write("a.csv.done", InboxFixture.markerFor("a.csv", csv, 3).getBytes(UTF_8));
    Batch batch = readAll(fx.connector()).get(0);
    assertThat(batch.payload().manifest().recordCount()).isEqualTo(3);
  }

  @Test
  void acknowledgeMovesToTheFolderOfTheVerdict() throws IOException {
    byte[] one = InboxFixture.oneRow("T-1", "EX-1");
    byte[] two = InboxFixture.oneRow("T-2", "EX-2");
    byte[] three = InboxFixture.oneRow("T-3", "EX-3");
    fx.drop("a.csv", one);
    fx.drop("b.csv", two);
    fx.drop("c.csv", three);
    FileSourceConnector connector = fx.connector();
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> batches = stream.next(10);
      connector.verdict(batches.get(1).seq(), Verdict.REJECTED);
      connector.verdict(batches.get(2).seq(), Verdict.QUARANTINED);
      stream.acknowledge(batches.get(2).checkpoint());
    }
    assertThat(names(fx.inbox)).isEmpty();
    assertThat(names(fx.processed)).containsExactly("a.csv", "a.csv.done");
    assertThat(names(fx.rejected)).containsExactly("b.csv", "b.csv.done");
    assertThat(names(fx.quarantine)).containsExactly("c.csv", "c.csv.done");
    assertThat(Files.readAllBytes(fx.processed.resolve("a.csv"))).isEqualTo(one);
    assertThat(Files.readAllBytes(fx.rejected.resolve("b.csv"))).isEqualTo(two);
    assertThat(Files.readAllBytes(fx.quarantine.resolve("c.csv"))).isEqualTo(three);
  }

  @Test
  void nothingMovesBeforeTheAcknowledge() throws IOException {
    fx.drop("a.csv", InboxFixture.oneRow("T-1", "EX-1"));
    FileSourceConnector connector = fx.connector();
    try (BatchStream stream = connector.read(fromStart())) {
      stream.next(10);
      connector.verdict(1L, Verdict.REJECTED);
    }
    assertThat(names(fx.inbox)).containsExactly("a.csv", "a.csv.done");
    assertThat(names(fx.rejected)).isEmpty();
    assertThatThrownBy(() -> connector.verdict(99L, Verdict.REJECTED))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aFileDroppedAgainAfterItWasMovedGetsANewSeqAndNothingIsOverwritten() throws IOException {
    byte[] csv = InboxFixture.oneRow("T-1", "EX-1");
    fx.drop("a.csv", csv);
    FileSourceConnector connector = fx.connector();
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> first = stream.next(10);
      assertThat(first.get(0).seq()).isEqualTo(1L);
      stream.acknowledge(first.get(0).checkpoint());
    }
    assertThat(names(fx.processed)).containsExactly("a.csv", "a.csv.done");
    Instant movedAt = Files.getLastModifiedTime(fx.processed.resolve("a.csv")).toInstant();
    // the very same bytes are dropped again: a new file, a new seq, the moved copy untouched
    fx.drop("a.csv", csv);
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> again = stream.next(10);
      assertThat(again).hasSize(1);
      assertThat(again.get(0).seq()).isEqualTo(2L);
      stream.acknowledge(again.get(0).checkpoint());
    }
    assertThat(names(fx.inbox)).isEmpty();
    assertThat(names(fx.processed)).containsExactly("a.csv", "a.csv.done");
    assertThat(Files.readAllBytes(fx.processed.resolve("a.csv"))).isEqualTo(csv);
    assertThat(Files.getLastModifiedTime(fx.processed.resolve("a.csv")).toInstant())
        .isEqualTo(movedAt);
  }

  @Test
  void aSameNameWithOtherBytesKeepsBothCopies() throws IOException {
    byte[] first = InboxFixture.oneRow("T-1", "EX-1");
    byte[] second = InboxFixture.oneRow("T-2", "EX-2");
    byte[] third = InboxFixture.oneRow("T-3", "EX-3");
    FileSourceConnector connector = fx.connector();
    for (byte[] csv : List.of(first, second, third)) {
      fx.drop("a.csv", csv);
      try (BatchStream stream = connector.read(fromStart())) {
        List<Batch> batches = stream.next(10);
        assertThat(batches).hasSize(1);
        stream.acknowledge(batches.get(0).checkpoint());
      }
    }
    assertThat(names(fx.inbox)).isEmpty();
    assertThat(names(fx.processed))
        .containsExactly(
            "a.1.csv", "a.1.csv.done", "a.2.csv", "a.2.csv.done", "a.csv", "a.csv.done");
    assertThat(Files.readAllBytes(fx.processed.resolve("a.csv"))).isEqualTo(first);
    assertThat(Files.readAllBytes(fx.processed.resolve("a.1.csv"))).isEqualTo(second);
    assertThat(Files.readAllBytes(fx.processed.resolve("a.2.csv"))).isEqualTo(third);
  }

  @Test
  void theMarkerAlwaysFollowsTheNameOfItsCsvWhicheverNameIsTaken() throws IOException {
    // only the CSV name is taken: both go to the next free number, together
    Files.write(fx.processed.resolve("a.csv"), "old".getBytes(UTF_8));
    byte[] one = InboxFixture.oneRow("T-1", "EX-1");
    fx.drop("a.csv", one);
    FileSourceConnector connector = fx.connector();
    try (BatchStream stream = connector.read(fromStart())) {
      stream.acknowledge(stream.next(10).get(0).checkpoint());
    }
    assertThat(names(fx.processed)).containsExactly("a.1.csv", "a.1.csv.done", "a.csv");
    // only the marker name is taken: the free CSV name is not used alone
    Files.write(fx.processed.resolve("b.csv.done"), "old".getBytes(UTF_8));
    byte[] two = InboxFixture.oneRow("T-2", "EX-2");
    fx.drop("b.csv", two);
    try (BatchStream stream = connector.read(fromStart())) {
      stream.acknowledge(stream.next(10).get(0).checkpoint());
    }
    assertThat(names(fx.processed))
        .containsExactly(
            "a.1.csv", "a.1.csv.done", "a.csv", "b.1.csv", "b.1.csv.done", "b.csv.done");
    assertThat(Files.readAllBytes(fx.processed.resolve("b.1.csv"))).isEqualTo(two);
    assertThat(Files.readAllBytes(fx.processed.resolve("b.csv.done")))
        .isEqualTo("old".getBytes(UTF_8));
  }

  @Test
  void aFileRewrittenAfterDeliveryIsNotMovedUnreadAndIsDeliveredAgainWithANewSeq()
      throws IOException {
    byte[] v1 = InboxFixture.oneRow("T-1", "EX-1");
    byte[] v2 = InboxFixture.oneRow("T-2", "EX-2");
    fx.drop("a.csv", v1);
    FileSourceConnector connector = fx.connector();
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> delivered = stream.next(10);
      assertThat(delivered.get(0).payload().bytes()).isEqualTo(v1);
      // the producer corrects the file after the delivery and before the acknowledge
      fx.drop("a.csv", v2);
      stream.acknowledge(delivered.get(0).checkpoint());
    }
    // the unread v2 and its marker stay in the inbox; nothing was moved
    assertThat(names(fx.inbox)).containsExactly("a.csv", "a.csv.done");
    assertThat(Files.readAllBytes(fx.inbox.resolve("a.csv"))).isEqualTo(v2);
    assertThat(names(fx.processed)).isEmpty();
    // a new read delivers v2 as a new file
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> again = stream.next(10);
      assertThat(again).hasSize(1);
      assertThat(again.get(0).seq()).isEqualTo(2L);
      assertThat(again.get(0).payload().bytes()).isEqualTo(v2);
      stream.acknowledge(again.get(0).checkpoint());
    }
    assertThat(names(fx.inbox)).isEmpty();
    assertThat(Files.readAllBytes(fx.processed.resolve("a.csv"))).isEqualTo(v2);
  }

  @Test
  void aFileReplacedByAnotherThingAfterDeliveryIsNotMoved() throws IOException {
    fx.drop("a.csv", InboxFixture.oneRow("T-1", "EX-1"));
    FileSourceConnector connector = fx.connector();
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> delivered = stream.next(10);
      Files.delete(fx.inbox.resolve("a.csv"));
      Files.createDirectory(fx.inbox.resolve("a.csv"));
      stream.acknowledge(delivered.get(0).checkpoint());
    }
    assertThat(names(fx.inbox)).containsExactly("a.csv", "a.csv.done");
    assertThat(names(fx.processed)).isEmpty();
  }

  @Test
  void aCopyAndDeleteFallbackStillPlacesTheFileAndLeavesNoTemporaryName() throws IOException {
    byte[] csv = InboxFixture.oneRow("T-1", "EX-1");
    fx.drop("a.csv", csv);
    // a leftover of an earlier crash in the same fallback must not block the retry
    Files.write(fx.processed.resolve(".a.csv.part"), "half".getBytes(UTF_8));
    FileSourceConnector connector = connectorWithoutHardLinks();
    try (BatchStream stream = connector.read(fromStart())) {
      stream.acknowledge(stream.next(10).get(0).checkpoint());
    }
    assertThat(names(fx.inbox)).isEmpty();
    assertThat(names(fx.processed)).containsExactly("a.csv", "a.csv.done");
    assertThat(Files.readAllBytes(fx.processed.resolve("a.csv"))).isEqualTo(csv);
  }

  @Test
  void theFallbackNeverOverwritesAndKeepsBothCopies() throws IOException {
    byte[] first = InboxFixture.oneRow("T-1", "EX-1");
    byte[] second = InboxFixture.oneRow("T-2", "EX-2");
    FileSourceConnector connector = connectorWithoutHardLinks();
    for (byte[] csv : List.of(first, second)) {
      fx.drop("a.csv", csv);
      try (BatchStream stream = connector.read(fromStart())) {
        stream.acknowledge(stream.next(10).get(0).checkpoint());
      }
    }
    assertThat(names(fx.processed))
        .containsExactly("a.1.csv", "a.1.csv.done", "a.csv", "a.csv.done");
    assertThat(Files.readAllBytes(fx.processed.resolve("a.csv"))).isEqualTo(first);
    assertThat(Files.readAllBytes(fx.processed.resolve("a.1.csv"))).isEqualTo(second);
  }

  private FileSourceConnector connectorWithoutHardLinks() {
    return new FileSourceConnector(
        fx.inbox,
        fx.processed,
        fx.rejected,
        fx.quarantine,
        fx.clock,
        "trades",
        new ReadLimits(100, 1_048_576L),
        FileSourceConnector::listCsv,
        (link, existing) -> {
          throw new UnsupportedOperationException();
        });
  }

  @Test
  void aStreamAcknowledgesOnlyTheFilesItDelivered() throws IOException {
    for (int i = 1; i <= 2; i++) {
      fx.drop("f" + i + ".csv", InboxFixture.oneRow("T-" + i, "EX-" + i));
    }
    FileSourceConnector connector = fx.connector();
    try (BatchStream a = connector.read(fromStart())) {
      Batch first = a.next(1).get(0);
      try (BatchStream b =
          connector.read(new ReadRequest(Optional.of(first.checkpoint()), limits()))) {
        // b delivers only seq 2: it cannot acknowledge seq 1, and its acknowledge of seq 2 does
        // not move seq 1, which only a delivered
        assertThatThrownBy(() -> b.acknowledge(first.checkpoint()))
            .isInstanceOf(IllegalArgumentException.class);
        Batch second = b.next(10).get(0);
        assertThat(second.seq()).isEqualTo(2L);
        b.acknowledge(second.checkpoint());
        assertThat(names(fx.inbox)).containsExactly("f1.csv", "f1.csv.done");
        assertThat(names(fx.processed)).containsExactly("f2.csv", "f2.csv.done");
      }
      a.acknowledge(first.checkpoint());
    }
    assertThat(names(fx.inbox)).isEmpty();
    assertThat(names(fx.processed))
        .containsExactly("f1.csv", "f1.csv.done", "f2.csv", "f2.csv.done");
  }

  private static ReadLimits limits() {
    return new ReadLimits(100, 1_000_000L);
  }

  @Test
  void aCapAboveTheCeilingIsRefusedAndNoFailureNamesAPathOrChainsACause() throws IOException {
    assertThatThrownBy(() -> fx.connector(16L * 1_048_576L + 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(fx.connector(16L * 1_048_576L)).isNotNull();
    // the inbox vanishes: the failure is a fixed text without the path and without a cause
    FileSourceConnector connector = fx.connector();
    Files.delete(fx.inbox);
    assertThatThrownBy(() -> readAll(connector))
        .isInstanceOf(IllegalStateException.class)
        .hasNoCause()
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain(root.toString()));
  }

  @Test
  void theSmallestUnusedSuffixIsTakenAndAnOccupiedOneIsNeverReplaced() throws IOException {
    Files.write(fx.processed.resolve("a.csv"), "old".getBytes(UTF_8));
    Files.write(fx.processed.resolve("a.2.csv"), "squatter".getBytes(UTF_8));
    byte[] csv = InboxFixture.oneRow("T-1", "EX-1");
    fx.drop("a.csv", csv);
    FileSourceConnector connector = fx.connector();
    try (BatchStream stream = connector.read(fromStart())) {
      stream.acknowledge(stream.next(10).get(0).checkpoint());
    }
    assertThat(Files.readAllBytes(fx.processed.resolve("a.csv"))).isEqualTo("old".getBytes(UTF_8));
    assertThat(Files.readAllBytes(fx.processed.resolve("a.2.csv")))
        .isEqualTo("squatter".getBytes(UTF_8));
    assertThat(Files.readAllBytes(fx.processed.resolve("a.1.csv"))).isEqualTo(csv);
  }

  @Test
  void aCumulativeAcknowledgeMovesEveryEarlierFile() throws IOException {
    for (int i = 1; i <= 4; i++) {
      fx.drop("f" + i + ".csv", InboxFixture.oneRow("T-" + i, "EX-" + i));
    }
    FileSourceConnector connector = fx.connector();
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> batches = stream.next(10);
      stream.acknowledge(batches.get(2).checkpoint());
    }
    assertThat(names(fx.processed))
        .containsExactly("f1.csv", "f1.csv.done", "f2.csv", "f2.csv.done", "f3.csv", "f3.csv.done");
    assertThat(names(fx.inbox)).containsExactly("f4.csv", "f4.csv.done");
    // an acknowledge twice is harmless and moves nothing more
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> rest = stream.next(10);
      assertThat(rest).extracting(Batch::seq).containsExactly(4L);
      stream.acknowledge(rest.get(0).checkpoint());
      stream.acknowledge(rest.get(0).checkpoint());
    }
    assertThat(names(fx.inbox)).isEmpty();
  }

  @Test
  void anAcknowledgeSurvivesASourceFileThatIsAlreadyGone() throws IOException {
    fx.drop("a.csv", InboxFixture.oneRow("T-1", "EX-1"));
    FileSourceConnector connector = fx.connector();
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> batches = stream.next(10);
      Files.delete(fx.inbox.resolve("a.csv"));
      stream.acknowledge(batches.get(0).checkpoint());
    }
    assertThat(names(fx.inbox)).isEmpty();
    assertThat(names(fx.processed)).containsExactly("a.csv.done");
  }

  @Test
  void filesAreReadInNameOrderWhateverTheListingOrder() {
    for (String name : List.of("b.csv", "c.csv", "a.csv")) {
      fx.drop(name, InboxFixture.oneRow("T-" + name, "EX-" + name));
    }
    FileSourceConnector reversed =
        new FileSourceConnector(
            fx.inbox,
            fx.processed,
            fx.rejected,
            fx.quarantine,
            fx.clock,
            "trades",
            new ReadLimits(100, 1_048_576L),
            dir -> {
              List<Path> listed = new ArrayList<>(FileSourceConnector.listCsv(dir));
              listed.sort(Collections.reverseOrder());
              return listed;
            });
    List<Batch> batches = readAll(reversed);
    assertThat(batches)
        .extracting(Batch::sourceCoordinates)
        .containsExactly("file:a.csv", "file:b.csv", "file:c.csv");
    assertThat(batches).extracting(Batch::seq).containsExactly(1L, 2L, 3L);
  }

  @Test
  void redeliveryAfterACrashReadsTheFileAgain() {
    byte[] csv = InboxFixture.oneRow("T-1", "EX-1");
    fx.drop("a.csv", csv);
    FileSourceConnector connector = fx.connector();
    List<Batch> first = readAll(connector);
    // the process dies before the acknowledge: the same instance and a fresh one see the file
    List<Batch> sameInstance = readAll(connector);
    List<Batch> freshInstance = readAll(fx.connector());
    assertThat(sameInstance).isEqualTo(first);
    assertThat(freshInstance).hasSize(1);
    assertThat(freshInstance.get(0).payload()).isEqualTo(first.get(0).payload());
    assertThat(freshInstance.get(0).seq()).isEqualTo(first.get(0).seq());
  }

  @Test
  void theCheckpointResumesAfterAnAcknowledgedFile() throws IOException {
    for (int i = 1; i <= 3; i++) {
      fx.drop("f" + i + ".csv", InboxFixture.oneRow("T-" + i, "EX-" + i));
    }
    FileSourceConnector connector = fx.connector();
    Checkpoint second;
    try (BatchStream stream = connector.read(fromStart())) {
      second = stream.next(10).get(1).checkpoint();
      stream.acknowledge(second);
    }
    assertThat(names(fx.inbox)).containsExactly("f3.csv", "f3.csv.done");
    try (BatchStream resumed =
        connector.read(new ReadRequest(Optional.of(second), new ReadLimits(100, 1_000_000L)))) {
      assertThat(resumed.next(10)).extracting(Batch::seq).containsExactly(3L);
    }
  }

  @Test
  void aCheckpointBeyondTheHighestSeqThisInstanceIssuedIsRefused() {
    fx.drop("a.csv", InboxFixture.oneRow("T-1", "EX-1"));
    FileSourceConnector connector = fx.connector();
    Checkpoint ahead = new Checkpoint("trades", ByteBuffer.allocate(8).putLong(1L).array());
    // nothing was issued yet: seq 1 is not a position of this instance
    assertThatThrownBy(
            () -> connector.read(new ReadRequest(Optional.of(ahead), new ReadLimits(1, 10L))))
        .isInstanceOf(IllegalArgumentException.class);
    readAll(connector);
    // seq 1 was issued: resuming after it is fine and delivers nothing; seq 2 is still ahead
    try (BatchStream resumed = connector.read(new ReadRequest(Optional.of(ahead), limits()))) {
      assertThat(resumed.next(10)).isEmpty();
    }
    Checkpoint tooFar = new Checkpoint("trades", ByteBuffer.allocate(8).putLong(2L).array());
    assertThatThrownBy(
            () -> connector.read(new ReadRequest(Optional.of(tooFar), new ReadLimits(1, 10L))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aCheckpointSkipsTheFilesUpToItWithoutReadingThem() {
    for (int i = 1; i <= 3; i++) {
      fx.drop("f" + i + ".csv", InboxFixture.oneRow("T-" + i, "EX-" + i));
    }
    FileSourceConnector connector = fx.connector();
    Checkpoint second;
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> batches = stream.next(10);
      second = batches.get(1).checkpoint();
    }
    long before = connector.bytesRead();
    try (BatchStream resumed =
        connector.read(new ReadRequest(Optional.of(second), new ReadLimits(100, 1_000_000L)))) {
      assertThat(resumed.next(10)).extracting(Batch::seq).containsExactly(3L);
    }
    // the skipped files are not read again
    assertThat(connector.bytesRead() - before).isLessThan(before);
    Checkpoint foreign = new Checkpoint("elsewhere", ByteBuffer.allocate(8).putLong(1L).array());
    assertThatThrownBy(
            () -> connector.read(new ReadRequest(Optional.of(foreign), new ReadLimits(1, 10L))))
        .isInstanceOf(IllegalArgumentException.class);
    Checkpoint shortState = new Checkpoint("trades", new byte[] {1, 2});
    assertThatThrownBy(
            () -> connector.read(new ReadRequest(Optional.of(shortState), new ReadLimits(1, 10L))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void checkNeedsTheFourFoldersAndNamesNoPath() throws IOException {
    FileSourceConnector connector = fx.connector();
    assertThat(connector.check().ok()).isTrue();
    Files.delete(fx.quarantine);
    assertThat(connector.check().ok()).isFalse();
    assertThat(connector.check().detail()).doesNotContain(root.toString());
    assertThat(connector.spec().name()).isNotBlank();
    assertThat(connector.discover().streams()).hasSize(1);
  }

  // ------------------------------------------------------------------ seams and helpers

  private FileSourceConnector connectorWith(Seams seams) {
    return new FileSourceConnector(
        fx.inbox,
        fx.processed,
        fx.rejected,
        fx.quarantine,
        fx.clock,
        "trades",
        new ReadLimits(100, 1_048_576L),
        seams);
  }

  /** A reader that fails with an IOException for named files while armed, and counts the bytes. */
  private static final class SpyReader implements Reader {
    private final Reader real = Seams.defaults().reader();
    final Set<String> noAttributes = new HashSet<>();
    final Set<String> noRead = new HashSet<>();
    long bytes;

    @Override
    public BasicFileAttributes attributes(Path path) throws IOException {
      if (noAttributes.contains(path.getFileName().toString())) {
        throw new IOException("injected");
      }
      return real.attributes(path);
    }

    @Override
    public byte[] read(Path path, int limit) throws IOException {
      if (noRead.contains(path.getFileName().toString())) {
        throw new IOException("injected");
      }
      byte[] out = real.read(path, limit);
      bytes += out.length;
      return out;
    }

    void heal() {
      noAttributes.clear();
      noRead.clear();
    }
  }

  /** A remover that refuses to delete named files while armed. */
  private static final class RefusingRemover implements Remover {
    final Set<String> refuse = new HashSet<>();

    @Override
    public void delete(Path path) throws IOException {
      if (refuse.contains(path.getFileName().toString())) {
        throw new IOException("injected");
      }
      Files.delete(path);
    }
  }

  /** What a producer does to re-drop a file: a temporary name, then a rename over the old name. */
  private static void replaceByRename(Path file, byte[] bytes) throws IOException {
    Path temporary = file.resolveSibling(file.getFileName() + ".swap");
    Files.write(temporary, bytes);
    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
  }

  private static void symlinkOrSkip(Path link, Path target) {
    boolean created;
    try {
      Files.createSymbolicLink(link, target);
      created = true;
    } catch (IOException | UnsupportedOperationException | SecurityException e) {
      created = false;
    }
    Assumptions.assumeTrue(created, "a symbolic link cannot be created on this host");
  }

  private static IngestReport ingestWith(IngestFixture ifx, FileSourceConnector connector) {
    ReadRequest request = new ReadRequest(Optional.empty(), new ReadLimits(100, 1_000_000L));
    try (BatchStream stream = connector.read(request)) {
      return ifx.ingest.ingest(stream, connector, 100);
    }
  }

  private static FileSourceConnector connectorOver(IngestFixture ifx, Seams seams) {
    return new FileSourceConnector(
        ifx.inbox.inbox,
        ifx.inbox.processed,
        ifx.inbox.rejected,
        ifx.inbox.quarantine,
        ifx.clock,
        "trades",
        new ReadLimits(100, 1_048_576L),
        seams);
  }

  private static List<Dq> pending(IngestFixture ifx) {
    return ifx.dq.list().stream().filter(d -> d.kind() == Dq.Kind.COMPLETION_PENDING).toList();
  }

  private void dropIngestible(IngestFixture ifx, String name) {
    String id = name.substring(0, name.length() - ".csv".length());
    ifx.drop(name, IngestFixture.csv(IngestFixture.row("T-" + id, 1, "EX-" + id, "10")), 1, "10");
  }

  // ------------------------------------------------------------------ the limits of Convention 7

  @Test
  void aValidMarkerOfExactly4096BytesIsReadAndOneOf4097BytesWaits() throws IOException {
    byte[] csv = InboxFixture.oneRow("T-1", "EX-1");
    fx.dropWithMarker("fits.csv", csv, InboxFixture.paddedMarkerFor("fits.csv", csv, 4096));
    fx.dropWithMarker("over.csv", csv, InboxFixture.paddedMarkerFor("over.csv", csv, 4097));
    assertThat(Files.size(fx.inbox.resolve("fits.csv.done"))).isEqualTo(4096L);
    assertThat(Files.size(fx.inbox.resolve("over.csv.done"))).isEqualTo(4097L);
    FileSourceConnector connector = fx.connector();
    assertThat(readAll(connector))
        .extracting(Batch::sourceCoordinates)
        .containsExactly("file:fits.csv");
    assertThat(connector.waiting())
        .extracting(Waiting::file, Waiting::reason)
        .containsExactly(tuple("over.csv", Reason.MARKER_INVALID));
    assertThat(connector.bytesRead()).isEqualTo(csv.length);
  }

  @Test
  void aFileNameOf128CharactersIsReadAndOneOf129WaitsAsNameInvalid() {
    String longest = "a".repeat(124) + ".csv";
    String tooLong = "b".repeat(125) + ".csv";
    assertThat(longest).hasSize(128);
    assertThat(tooLong).hasSize(129);
    byte[] csv = InboxFixture.oneRow("T-1", "EX-1");
    fx.drop(longest, csv);
    fx.drop(tooLong, InboxFixture.oneRow("T-2", "EX-2"));
    FileSourceConnector connector = fx.connector();
    assertThat(readAll(connector))
        .extracting(Batch::sourceCoordinates)
        .containsExactly("file:" + longest);
    assertThat(connector.waiting())
        .extracting(Waiting::file, Waiting::reason)
        .containsExactly(tuple(tooLong, Reason.NAME_INVALID));
    assertThat(connector.bytesRead()).isEqualTo(csv.length);
  }

  @Test
  void anUpperCaseExtensionWaitsAsNameInvalidAndIsReported() {
    byte[] ok = InboxFixture.oneRow("T-1", "EX-1");
    fx.drop("ok.csv", ok);
    fx.drop("TRADES.CSV", InboxFixture.oneRow("T-2", "EX-2"));
    fx.drop("Mixed.Csv", InboxFixture.oneRow("T-3", "EX-3"));
    FileSourceConnector connector = fx.connector();
    assertThat(readAll(connector))
        .extracting(Batch::sourceCoordinates)
        .containsExactly("file:ok.csv");
    assertThat(connector.waiting())
        .extracting(Waiting::file, Waiting::reason)
        .containsExactly(
            tuple("Mixed.Csv", Reason.NAME_INVALID), tuple("TRADES.CSV", Reason.NAME_INVALID));
    assertThat(connector.bytesRead()).isEqualTo(ok.length);

    // the ingest stage turns the wait into an item once it is past the completion deadline
    IngestFixture ifx = new IngestFixture(root.resolve("ingest"));
    dropIngestible(ifx, "TRADES.CSV");
    assertThat(ifx.run().batches()).isEmpty();
    assertThat(pending(ifx)).isEmpty();
    ifx.clock.advance(IngestFixture.DEADLINE.plusSeconds(1));
    assertThat(ifx.run().batches()).isEmpty();
    assertThat(pending(ifx))
        .extracting(Dq::code, Dq::scope)
        .containsExactly(tuple("NAME_INVALID", Sha256.ofUtf8("TRADES.CSV").hex()));
    assertThat(ifx.names(ifx.inbox.inbox)).containsExactly("TRADES.CSV", "TRADES.CSV.done");
  }

  // ------------------------------------------------------------------ a bad file never stalls

  enum Failure {
    CSV_ATTRIBUTES,
    MARKER_READ,
    CSV_READ
  }

  @ParameterizedTest
  @EnumSource(Failure.class)
  void anUnreadableFileWaitsAsUnreadableAndTheFilesAroundItAreStillRead(Failure failure) {
    byte[] a = InboxFixture.oneRow("T-1", "EX-1");
    byte[] b = InboxFixture.oneRow("T-2", "EX-2");
    byte[] c = InboxFixture.oneRow("T-3", "EX-3");
    fx.drop("a.csv", a);
    fx.drop("b.csv", b);
    fx.drop("c.csv", c);
    SpyReader reader = new SpyReader();
    switch (failure) {
      case CSV_ATTRIBUTES -> reader.noAttributes.add("b.csv");
      case MARKER_READ -> reader.noRead.add("b.csv.done");
      case CSV_READ -> reader.noRead.add("b.csv");
    }
    FileSourceConnector connector = connectorWith(Seams.defaults().withReader(reader));

    List<Batch> batches = readAll(connector);

    assertThat(batches)
        .extracting(Batch::sourceCoordinates)
        .containsExactly("file:a.csv", "file:c.csv");
    assertThat(connector.waiting())
        .containsExactly(
            new Waiting("b.csv", Instant.parse("2026-10-31T12:00:00Z"), Reason.UNREADABLE));
    // a later tick finds the same fault, the same first instant, and still reads the others
    fx.clock.advance(Duration.ofMinutes(5));
    assertThat(readAll(connector)).hasSize(2);
    assertThat(connector.waiting().get(0).firstSeen())
        .isEqualTo(Instant.parse("2026-10-31T12:00:00Z"));
    // the fault clears: the file is a batch on the next tick and the wait ends
    reader.heal();
    List<Batch> healed = readAll(connector);
    assertThat(healed)
        .extracting(Batch::sourceCoordinates)
        .containsExactly("file:a.csv", "file:b.csv", "file:c.csv");
    assertThat(healed.get(1).payload().bytes()).isEqualTo(b);
    assertThat(connector.waiting()).isEmpty();
  }

  @Test
  void anUnreadableFileIsReportedAfterTheDeadlineAndTheFilesAroundItAreIngested() {
    IngestFixture ifx = new IngestFixture(root.resolve("ingest"));
    for (String name : List.of("a.csv", "b.csv", "c.csv")) {
      dropIngestible(ifx, name);
    }
    SpyReader reader = new SpyReader();
    reader.noRead.add("b.csv");
    FileSourceConnector connector = connectorOver(ifx, Seams.defaults().withReader(reader));

    IngestReport report = ingestWith(ifx, connector);

    assertThat(report.batches()).extracting(BatchReport::file).containsExactly("a.csv", "c.csv");
    assertThat(report.batches()).allMatch(b -> b.ingested() == 1);
    assertThat(ifx.names(ifx.inbox.inbox)).containsExactly("b.csv", "b.csv.done");
    assertThat(pending(ifx)).isEmpty();
    ifx.clock.advance(IngestFixture.DEADLINE.plusSeconds(1));
    assertThat(ingestWith(ifx, connector).batches()).isEmpty();
    assertThat(pending(ifx))
        .extracting(Dq::code, Dq::scope)
        .containsExactly(tuple("UNREADABLE", Sha256.ofUtf8("b.csv").hex()));
    reader.heal();
    assertThat(ingestWith(ifx, connector).batches())
        .extracting(BatchReport::file)
        .containsExactly("b.csv");
    assertThat(ifx.names(ifx.inbox.inbox)).isEmpty();
  }

  @Test
  void aSourceThatCannotBeDeletedDoesNotBlockTheNextFileAndIsRetried() throws IOException {
    byte[] a = InboxFixture.oneRow("T-1", "EX-1");
    byte[] b = InboxFixture.oneRow("T-2", "EX-2");
    fx.drop("a.csv", a);
    fx.drop("b.csv", b);
    RefusingRemover remover = new RefusingRemover();
    remover.refuse.add("a.csv");
    FileSourceConnector connector = connectorWith(Seams.defaults().withRemover(remover));
    Instant first = fx.clock.now();
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> batches = stream.next(10);
      assertThatCode(() -> stream.acknowledge(batches.get(1).checkpoint()))
          .doesNotThrowAnyException();
    }
    // b was moved in spite of a; a is still in the inbox and its bytes are also in processed
    assertThat(names(fx.inbox)).containsExactly("a.csv", "a.csv.done");
    assertThat(names(fx.processed)).containsExactly("a.csv", "b.csv", "b.csv.done");
    assertThat(connector.waiting())
        .containsExactly(new Waiting("a.csv", first, Reason.MOVE_FAILED));

    // still failing on a later tick: the same instant of first failure, nothing thrown
    fx.clock.advance(Duration.ofMinutes(10));
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> again = stream.next(10);
      assertThat(again).extracting(Batch::seq).containsExactly(1L);
      assertThatCode(() -> stream.acknowledge(again.get(0).checkpoint()))
          .doesNotThrowAnyException();
    }
    assertThat(connector.waiting())
        .containsExactly(new Waiting("a.csv", first, Reason.MOVE_FAILED));

    // the lock is released: the retried move finds its own earlier link and finishes
    remover.refuse.clear();
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> again = stream.next(10);
      assertThat(again).extracting(Batch::seq).containsExactly(1L);
      stream.acknowledge(again.get(0).checkpoint());
    }
    assertThat(names(fx.inbox)).isEmpty();
    assertThat(names(fx.processed)).containsExactly("a.csv", "a.csv.done", "b.csv", "b.csv.done");
    assertThat(Files.readAllBytes(fx.processed.resolve("a.csv"))).isEqualTo(a);
    assertThat(Files.readAllBytes(fx.processed.resolve("b.csv"))).isEqualTo(b);
    assertThat(connector.waiting()).isEmpty();
  }

  @Test
  void aMarkerThatCannotBeDeletedIsRetriedByTheNextAcknowledgeOfTheStream() throws IOException {
    fx.drop("a.csv", InboxFixture.oneRow("T-1", "EX-1"));
    RefusingRemover remover = new RefusingRemover();
    remover.refuse.add("a.csv.done");
    FileSourceConnector connector = connectorWith(Seams.defaults().withRemover(remover));
    try (BatchStream stream = connector.read(fromStart())) {
      Checkpoint checkpoint = stream.next(10).get(0).checkpoint();
      assertThatCode(() -> stream.acknowledge(checkpoint)).doesNotThrowAnyException();
      // the CSV is moved, the marker is still in the inbox and the failure is listed
      assertThat(names(fx.inbox)).containsExactly("a.csv.done");
      assertThat(connector.waiting())
          .extracting(Waiting::file, Waiting::reason)
          .containsExactly(tuple("a.csv", Reason.MOVE_FAILED));
      remover.refuse.clear();
      stream.acknowledge(checkpoint);
    }
    assertThat(names(fx.inbox)).isEmpty();
    assertThat(names(fx.processed)).containsExactly("a.csv", "a.csv.done");
    assertThat(connector.waiting()).isEmpty();
  }

  @Test
  void aMarkerLeftByAFailedMoveIsRetriedWhenTheNextStreamIsOpenedAndTheFileGetsAFreshSeq()
      throws IOException {
    byte[] csv = InboxFixture.oneRow("T-1", "EX-1");
    fx.drop("a.csv", csv);
    RefusingRemover remover = new RefusingRemover();
    remover.refuse.add("a.csv.done");
    FileSourceConnector connector = connectorWith(Seams.defaults().withRemover(remover));
    try (BatchStream stream = connector.read(fromStart())) {
      stream.acknowledge(stream.next(10).get(0).checkpoint());
    }
    // the CSV is moved, its marker is left behind and the failure is listed
    assertThat(names(fx.inbox)).containsExactly("a.csv.done");
    assertThat(connector.waiting())
        .extracting(Waiting::file, Waiting::reason)
        .containsExactly(tuple("a.csv", Reason.MOVE_FAILED));
    remover.refuse.clear();

    // the next tick opens a new stream: it delivers nothing and its open retries the marker
    try (BatchStream next = connector.read(fromStart())) {
      assertThat(next.next(10)).isEmpty();
    }
    assertThat(names(fx.inbox)).isEmpty();
    assertThat(names(fx.processed)).containsExactly("a.csv", "a.csv.done");
    assertThat(connector.waiting()).isEmpty();

    // the entry has left the flight: the same bytes dropped again are a new file, a new number
    fx.drop("a.csv", csv);
    assertThat(readAll(connector)).extracting(Batch::seq).containsExactly(2L);
  }

  @Test
  void aMarkerWhoseRetryKeepsFailingStaysListedAsMoveFailedAndIsReportedAfterTheDeadline() {
    IngestFixture ifx = new IngestFixture(root.resolve("ingest"));
    dropIngestible(ifx, "a.csv");
    RefusingRemover remover = new RefusingRemover();
    remover.refuse.add("a.csv.done");
    FileSourceConnector connector = connectorOver(ifx, Seams.defaults().withRemover(remover));
    Instant first = ifx.clock.now();

    assertThat(ingestWith(ifx, connector).batches())
        .extracting(BatchReport::file)
        .containsExactly("a.csv");
    assertThat(ifx.names(ifx.inbox.inbox)).containsExactly("a.csv.done");
    assertThat(pending(ifx)).isEmpty();

    // a later tick: a new stream, the CSV is no longer in the inbox, the retry fails again
    ifx.clock.advance(IngestFixture.DEADLINE.plusSeconds(1));
    assertThat(ingestWith(ifx, connector).batches()).isEmpty();
    assertThat(connector.waiting())
        .containsExactly(new Waiting("a.csv", first, Reason.MOVE_FAILED));
    assertThat(pending(ifx))
        .extracting(Dq::code, Dq::scope)
        .containsExactly(tuple("MOVE_FAILED", Sha256.ofUtf8("a.csv").hex()));

    remover.refuse.clear();
    assertThat(ingestWith(ifx, connector).batches()).isEmpty();
    assertThat(ifx.names(ifx.inbox.inbox)).isEmpty();
    assertThat(ifx.names(ifx.inbox.processed)).containsExactly("a.csv", "a.csv.done");
    assertThat(connector.waiting()).isEmpty();
  }

  @Test
  void aMarkerWithNoCsvAndNoFailedMoveStaysSilentAndWhereItIs() throws IOException {
    byte[] csv = InboxFixture.oneRow("T-1", "EX-1");
    // a marker nobody delivered a CSV for
    fx.write("lone.csv.done", InboxFixture.markerFor("lone.csv", csv, 1).getBytes(UTF_8));
    // a delivered file whose CSV was taken away by somebody else before any acknowledge
    fx.drop("a.csv", csv);
    FileSourceConnector connector = fx.connector();
    try (BatchStream stream = connector.read(fromStart())) {
      assertThat(stream.next(10)).extracting(Batch::seq).containsExactly(1L);
      Files.delete(fx.inbox.resolve("a.csv"));
    }
    try (BatchStream next = connector.read(fromStart())) {
      assertThat(next.next(10)).isEmpty();
    }
    assertThat(connector.waiting()).isEmpty();
    assertThat(names(fx.inbox)).containsExactly("a.csv.done", "lone.csv.done");
    assertThat(names(fx.processed)).isEmpty();
  }

  @Test
  void aFileWhoseCsvIsStillInTheInboxIsNotRetriedByTheOpenOfTheNextStream() throws IOException {
    byte[] csv = InboxFixture.oneRow("T-1", "EX-1");
    fx.drop("a.csv", csv);
    RefusingRemover remover = new RefusingRemover();
    remover.refuse.add("a.csv");
    FileSourceConnector connector = connectorWith(Seams.defaults().withRemover(remover));
    try (BatchStream stream = connector.read(fromStart())) {
      stream.acknowledge(stream.next(10).get(0).checkpoint());
    }
    assertThat(names(fx.inbox)).containsExactly("a.csv", "a.csv.done");
    remover.refuse.clear();

    try (BatchStream next = connector.read(fromStart())) {
      // the open did not move it: the file is delivered again, with its number, and acknowledged
      assertThat(names(fx.inbox)).containsExactly("a.csv", "a.csv.done");
      List<Batch> again = next.next(10);
      assertThat(again).extracting(Batch::seq).containsExactly(1L);
      next.acknowledge(again.get(0).checkpoint());
    }
    assertThat(names(fx.inbox)).isEmpty();
    assertThat(names(fx.processed)).containsExactly("a.csv", "a.csv.done");
    assertThat(connector.waiting()).isEmpty();
  }

  @Test
  void noFreeNameForAMovedFileFailsThatFileOnlyAndNothingIsOverwritten() throws IOException {
    byte[] taken = "taken".getBytes(UTF_8);
    for (String name : List.of("a.csv", "a.1.csv", "a.2.csv")) {
      Files.write(fx.processed.resolve(name), taken);
    }
    fx.drop("a.csv", InboxFixture.oneRow("T-1", "EX-1"));
    fx.drop("b.csv", InboxFixture.oneRow("T-2", "EX-2"));
    FileSourceConnector connector = connectorWith(Seams.defaults().withMaxSuffix(2));
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> batches = stream.next(10);
      assertThatCode(() -> stream.acknowledge(batches.get(1).checkpoint()))
          .doesNotThrowAnyException();
    }
    assertThat(names(fx.inbox)).containsExactly("a.csv", "a.csv.done");
    assertThat(names(fx.processed))
        .containsExactly("a.1.csv", "a.2.csv", "a.csv", "b.csv", "b.csv.done");
    assertThat(Files.readAllBytes(fx.processed.resolve("a.csv"))).isEqualTo(taken);
    assertThat(connector.waiting())
        .extracting(Waiting::file, Waiting::reason)
        .containsExactly(tuple("a.csv", Reason.MOVE_FAILED));
  }

  @Test
  void aMissingTargetFolderIsAFailedMoveAndNotAFileThatIsGone() throws IOException {
    fx.drop("a.csv", InboxFixture.oneRow("T-1", "EX-1"));
    fx.drop("b.csv", InboxFixture.oneRow("T-2", "EX-2"));
    FileSourceConnector connector = fx.connector();
    Files.delete(fx.rejected);
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> batches = stream.next(10);
      connector.verdict(batches.get(1).seq(), Verdict.REJECTED);
      assertThatCode(() -> stream.acknowledge(batches.get(1).checkpoint()))
          .doesNotThrowAnyException();
    }
    // a was moved; b is still in the inbox and says why, instead of being taken for gone
    assertThat(names(fx.processed)).containsExactly("a.csv", "a.csv.done");
    assertThat(names(fx.inbox)).containsExactly("b.csv", "b.csv.done");
    assertThat(connector.waiting())
        .extracting(Waiting::file, Waiting::reason)
        .containsExactly(tuple("b.csv", Reason.MOVE_FAILED));
    // the folder is back: the next tick delivers b again and the move goes through
    Files.createDirectory(fx.rejected);
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> again = stream.next(10);
      assertThat(again).extracting(Batch::seq).containsExactly(2L);
      connector.verdict(again.get(0).seq(), Verdict.REJECTED);
      stream.acknowledge(again.get(0).checkpoint());
    }
    assertThat(names(fx.inbox)).isEmpty();
    assertThat(names(fx.rejected)).containsExactly("b.csv", "b.csv.done");
    assertThat(connector.waiting()).isEmpty();
  }

  @Test
  void aFailedMoveIsReportedAfterTheDeadlineAndRetriedByTheNextTick() {
    IngestFixture ifx = new IngestFixture(root.resolve("ingest"));
    dropIngestible(ifx, "a.csv");
    dropIngestible(ifx, "b.csv");
    RefusingRemover remover = new RefusingRemover();
    remover.refuse.add("a.csv");
    FileSourceConnector connector = connectorOver(ifx, Seams.defaults().withRemover(remover));

    IngestReport report = ingestWith(ifx, connector);

    assertThat(report.batches()).extracting(BatchReport::file).containsExactly("a.csv", "b.csv");
    assertThat(ifx.names(ifx.inbox.inbox)).containsExactly("a.csv", "a.csv.done");
    assertThat(pending(ifx)).isEmpty();
    ifx.clock.advance(IngestFixture.DEADLINE.plusSeconds(1));
    assertThatCode(() -> ingestWith(ifx, connector)).doesNotThrowAnyException();
    assertThat(pending(ifx))
        .extracting(Dq::code, Dq::scope)
        .containsExactly(tuple("MOVE_FAILED", Sha256.ofUtf8("a.csv").hex()));
    remover.refuse.clear();
    ingestWith(ifx, connector);
    assertThat(ifx.names(ifx.inbox.inbox)).isEmpty();
    assertThat(ifx.names(ifx.inbox.processed))
        .containsExactly("a.csv", "a.csv.done", "b.csv", "b.csv.done");
  }

  // ------------------------------------------------------------------ the swap during a move

  enum Swap {
    AFTER_THE_LINK,
    BEFORE_THE_LINK,
    BEFORE_THE_COPY
  }

  @ParameterizedTest
  @EnumSource(Swap.class)
  void aFilePutUnderTheNameWhileItIsMovedIsNotDeletedUnreadAndIsReadLater(Swap swap)
      throws IOException {
    byte[] r1 = InboxFixture.oneRow("T-1", "EX-1");
    byte[] r2 = InboxFixture.oneRow("T-2", "EX-2");
    fx.drop("a.csv", r1);
    AtomicBoolean once = new AtomicBoolean();
    FileSourceConnector.Linker linker =
        (link, existing) -> {
          boolean csv =
              existing.getFileName().toString().equals("a.csv") && once.compareAndSet(false, true);
          if (csv && swap != Swap.AFTER_THE_LINK) {
            replaceByRename(existing, r2);
            fx.write("a.csv.done", InboxFixture.markerFor("a.csv", r2, 1).getBytes(UTF_8));
          }
          if (swap == Swap.BEFORE_THE_COPY) {
            throw new UnsupportedOperationException();
          }
          Files.createLink(link, existing);
          if (csv && swap == Swap.AFTER_THE_LINK) {
            replaceByRename(existing, r2);
            fx.write("a.csv.done", InboxFixture.markerFor("a.csv", r2, 1).getBytes(UTF_8));
          }
        };
    FileSourceConnector connector = connectorWith(Seams.defaults().withLinker(linker));
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> first = stream.next(10);
      assertThat(first.get(0).payload().bytes()).isEqualTo(r1);
      assertThatCode(() -> stream.acknowledge(first.get(0).checkpoint()))
          .doesNotThrowAnyException();
    }
    // the unread replacement is still in the inbox; the link or copy that was made is gone
    assertThat(names(fx.inbox)).containsExactly("a.csv", "a.csv.done");
    assertThat(Files.readAllBytes(fx.inbox.resolve("a.csv"))).isEqualTo(r2);
    assertThat(names(fx.processed)).isEmpty();
    assertThat(connector.waiting()).isEmpty();
    // it is delivered as a new file with a new number, and moved when it is acknowledged
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> again = stream.next(10);
      assertThat(again).extracting(Batch::seq).containsExactly(2L);
      assertThat(again.get(0).payload().bytes()).isEqualTo(r2);
      stream.acknowledge(again.get(0).checkpoint());
    }
    assertThat(names(fx.inbox)).isEmpty();
    assertThat(Files.readAllBytes(fx.processed.resolve("a.csv"))).isEqualTo(r2);
    assertThat(names(fx.processed)).containsExactly("a.csv", "a.csv.done");
  }

  @Test
  void aMarkerReplacedWhileItIsMovedIsNotDeletedUnread() throws IOException {
    byte[] r1 = InboxFixture.oneRow("T-1", "EX-1");
    byte[] next =
        InboxFixture.markerFor("a.csv", InboxFixture.oneRow("T-2", "EX-2"), 1).getBytes(UTF_8);
    fx.drop("a.csv", r1);
    AtomicBoolean once = new AtomicBoolean();
    FileSourceConnector.Linker linker =
        (link, existing) -> {
          Files.createLink(link, existing);
          if (existing.getFileName().toString().equals("a.csv.done")
              && once.compareAndSet(false, true)) {
            replaceByRename(existing, next);
          }
        };
    FileSourceConnector connector = connectorWith(Seams.defaults().withLinker(linker));
    try (BatchStream stream = connector.read(fromStart())) {
      stream.acknowledge(stream.next(10).get(0).checkpoint());
    }
    // the CSV was moved as delivered; the replacement marker was not deleted and is not copied
    assertThat(names(fx.processed)).containsExactly("a.csv");
    assertThat(names(fx.inbox)).containsExactly("a.csv.done");
    assertThat(Files.readAllBytes(fx.inbox.resolve("a.csv.done"))).isEqualTo(next);
  }

  // ------------------------------------------------------------------ the collision search

  private static byte[] variant(int i) {
    return InboxFixture.oneRow(
        String.format(Locale.ROOT, "T-%04d", i), String.format(Locale.ROOT, "EX-%04d", i));
  }

  private static String copyName(String stem, int n) {
    return n == 0 ? stem + ".csv" : stem + "." + n + ".csv";
  }

  @Test
  void aCollisionSearchReadsEachSameSizeCandidateOnceAndNeverTheSourceAgain() throws IOException {
    byte[] mine = variant(9999);
    for (int i = 0; i < 300; i++) {
      Files.write(fx.processed.resolve(copyName("a", i)), variant(i));
    }
    assertThat(variant(0)).hasSameSizeAs(mine);
    fx.drop("a.csv", mine);
    SpyReader reader = new SpyReader();
    FileSourceConnector connector = connectorWith(Seams.defaults().withReader(reader));
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> batches = stream.next(10);
      long before = reader.bytes;
      stream.acknowledge(batches.get(0).checkpoint());
      // the source once, each of the 300 candidates once, the placed copy once
      assertThat(reader.bytes - before).isLessThanOrEqualTo(302L * mine.length);
    }
    assertThat(names(fx.inbox)).isEmpty();
    assertThat(Files.readAllBytes(fx.processed.resolve("a.300.csv"))).isEqualTo(mine);
    assertThat(fx.exists(fx.processed, "a.300.csv.done")).isTrue();
    assertThat(Files.readAllBytes(fx.processed.resolve("a.csv"))).isEqualTo(variant(0));
  }

  @Test
  void aCandidateOfAnotherSizeIsNeverRead() throws IOException {
    byte[] mine = variant(9999);
    byte[] longer = Arrays.copyOf(mine, mine.length + 1);
    for (int i = 0; i < 10; i++) {
      Files.write(fx.processed.resolve(copyName("a", i)), longer);
    }
    fx.drop("a.csv", mine);
    SpyReader reader = new SpyReader();
    FileSourceConnector connector = connectorWith(Seams.defaults().withReader(reader));
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> batches = stream.next(10);
      long before = reader.bytes;
      stream.acknowledge(batches.get(0).checkpoint());
      // the source and the placed copy, and not one of the ten candidates
      assertThat(reader.bytes - before).isLessThanOrEqualTo(2L * mine.length);
    }
    assertThat(Files.readAllBytes(fx.processed.resolve("a.10.csv"))).isEqualTo(mine);
  }

  @Test
  void theSearchStopsAtTheFirstCandidateThatHoldsTheSameBytes() throws IOException {
    byte[] mine = variant(9999);
    for (int i = 0; i < 50; i++) {
      Files.write(fx.processed.resolve(copyName("a", i)), i == 1 ? mine : variant(i));
    }
    fx.drop("a.csv", mine);
    SpyReader reader = new SpyReader();
    FileSourceConnector connector = connectorWith(Seams.defaults().withReader(reader));
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> batches = stream.next(10);
      long before = reader.bytes;
      stream.acknowledge(batches.get(0).checkpoint());
      // the source, two candidates, and the final comparison of the source with the second
      assertThat(reader.bytes - before).isLessThanOrEqualTo(5L * mine.length);
    }
    // the source is gone, nothing new was made for the CSV, the marker follows the CSV's name
    assertThat(names(fx.inbox)).isEmpty();
    assertThat(names(fx.processed)).hasSize(51).contains("a.1.csv.done");
    assertThat(fx.exists(fx.processed, "a.50.csv")).isFalse();
  }

  // ------------------------------------------------------------------ links are never followed

  @Test
  void aSymbolicLinkNamedCsvWaitsAsNotRegularAndIsNeverRead() throws IOException {
    byte[] csv = InboxFixture.oneRow("T-1", "EX-1");
    Path outside = Files.createDirectories(root.resolve("outside"));
    Path target = Files.write(outside.resolve("target.csv"), csv);
    symlinkOrSkip(fx.inbox.resolve("x.csv"), target);
    // a marker that is right for the bytes behind the link
    fx.write("x.csv.done", InboxFixture.markerFor("x.csv", csv, 1).getBytes(UTF_8));
    FileSourceConnector connector = fx.connector();
    assertThat(readAll(connector)).isEmpty();
    assertThat(connector.waiting())
        .extracting(Waiting::file, Waiting::reason)
        .containsExactly(tuple("x.csv", Reason.NOT_REGULAR));
    assertThat(connector.bytesRead()).isZero();
    assertThat(Files.readAllBytes(target)).isEqualTo(csv);
  }

  @Test
  void aSymbolicLinkMarkerIsInvalid() throws IOException {
    byte[] csv = InboxFixture.oneRow("T-1", "EX-1");
    Path outside = Files.createDirectories(root.resolve("outside"));
    Path real =
        Files.write(
            outside.resolve("real.done"), InboxFixture.markerFor("x.csv", csv, 1).getBytes(UTF_8));
    fx.write("x.csv", csv);
    symlinkOrSkip(fx.inbox.resolve("x.csv.done"), real);
    FileSourceConnector connector = fx.connector();
    assertThat(readAll(connector)).isEmpty();
    assertThat(connector.waiting())
        .extracting(Waiting::file, Waiting::reason)
        .containsExactly(tuple("x.csv", Reason.MARKER_INVALID));
    assertThat(connector.bytesRead()).isZero();
  }

  @Test
  void aCsvSwappedForASymbolicLinkBeforeTheAcknowledgeIsNotMoved() throws IOException {
    byte[] csv = InboxFixture.oneRow("T-1", "EX-1");
    fx.drop("a.csv", csv);
    Path outside = Files.createDirectories(root.resolve("outside"));
    // the very same bytes: a link that is followed would look like the delivered file
    Path target = Files.write(outside.resolve("target.csv"), csv);
    FileSourceConnector connector = fx.connector();
    try (BatchStream stream = connector.read(fromStart())) {
      List<Batch> delivered = stream.next(10);
      Files.delete(fx.inbox.resolve("a.csv"));
      symlinkOrSkip(fx.inbox.resolve("a.csv"), target);
      assertThatCode(() -> stream.acknowledge(delivered.get(0).checkpoint()))
          .doesNotThrowAnyException();
    }
    assertThat(Files.isSymbolicLink(fx.inbox.resolve("a.csv"))).isTrue();
    assertThat(names(fx.inbox)).containsExactly("a.csv", "a.csv.done");
    assertThat(names(fx.processed)).isEmpty();
    assertThat(Files.readAllBytes(target)).isEqualTo(csv);
    assertThat(connector.waiting()).isEmpty();
    // a new read lists the link as what it is and reads nothing
    long before = connector.bytesRead();
    assertThat(readAll(connector)).isEmpty();
    assertThat(connector.waiting())
        .extracting(Waiting::file, Waiting::reason)
        .containsExactly(tuple("a.csv", Reason.NOT_REGULAR));
    assertThat(connector.bytesRead()).isEqualTo(before);
  }

  // ------------------------------------------------------------------ the number of a file

  @Test
  void aFileRewrittenWithOtherBytesBeforeTheNextReadGetsANewSeqWithoutAnAcknowledge() {
    byte[] v1 = InboxFixture.oneRow("T-1", "EX-1");
    byte[] v2 = InboxFixture.oneRow("T-2", "EX-2");
    fx.drop("a.csv", v1);
    FileSourceConnector connector = fx.connector();
    assertThat(readAll(connector)).extracting(Batch::seq).containsExactly(1L);
    // the producer corrects the file; nobody has acknowledged the first delivery
    fx.drop("a.csv", v2);
    List<Batch> again = readAll(connector);
    assertThat(again).extracting(Batch::seq).containsExactly(2L);
    assertThat(again.get(0).payload().bytes()).isEqualTo(v2);
    // the same bytes read again keep their number
    assertThat(readAll(connector)).extracting(Batch::seq).containsExactly(2L);
  }
}
