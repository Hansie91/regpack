package dev.regpack.spikes.x.bench;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.spikes.x.TestDocuments;
import dev.regpack.spikes.x.xml.StaxRecordWriter;
import dev.regpack.spikes.x.xml.TxModel;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class WriterBenchTest {
  @Test
  void checksumFoldsTheWrittenBytes() {
    WriterBench first = new WriterBench(9L, 64);
    WriterBench same = new WriterBench(9L, 64);
    WriterBench otherSeed = new WriterBench(10L, 64);
    assertThat(first.checksum()).isZero();
    assertThat(first.runUnits(100)).isEqualTo(100);
    assertThat(same.runUnits(100)).isEqualTo(100);
    assertThat(otherSeed.runUnits(100)).isEqualTo(100);
    assertThat(first.checksum()).isNotZero().isEqualTo(same.checksum());
    assertThat(otherSeed.checksum()).isNotEqualTo(first.checksum());
    long before = first.checksum();
    first.runUnits(1);
    assertThat(first.checksum()).as("more work, another checksum").isNotEqualTo(before);
  }

  @Test
  void bytesWrittenAreTheBytesOfTheSameRecordsWrittenIndependently() {
    int ring = 8;
    int units = 20; // wraps the ring more than twice
    WriterBench bench = new WriterBench(4L, ring);
    assertThat(bench.runUnits(units)).isEqualTo(units);

    List<TxModel> records = TestDocuments.records(4L, ring);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    StaxRecordWriter writer = new StaxRecordWriter(bytes);
    writer.startDocument(WriterBench.MESSAGE_ID, WriterBench.CREATION_MICROS);
    for (int i = 0; i < units; i++) {
      writer.writeTx(records.get(i % ring));
    }
    writer.bytesWritten();
    assertThat(bench.bytesWritten()).isEqualTo(bytes.size());
    assertThat(bench.cycleUnits()).as("a steady stream, no periodic phase").isZero();
  }
}
