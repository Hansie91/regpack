package dev.regpack.flow.ingest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.flow.ingest.FileSourceConnector.Reason;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    fx.write("huge.csv.done", ("{\"pad\":\"" + "x".repeat(5000) + "\"}").getBytes(UTF_8));
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
}
