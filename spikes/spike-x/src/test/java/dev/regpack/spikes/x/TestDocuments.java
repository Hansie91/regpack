package dev.regpack.spikes.x;

import dev.regpack.bench.data.TradeRecord;
import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.spikes.x.xml.StaxRecordWriter;
import dev.regpack.spikes.x.xml.TxModel;
import dev.regpack.spikes.x.xml.XmlRecordGen;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Test helper: generated records and whole documents. */
public final class TestDocuments {
  private TestDocuments() {}

  public static List<TxModel> records(long seed, int count) {
    TradeRecordGen trades = new TradeRecordGen(seed);
    XmlRecordGen gen = new XmlRecordGen(seed);
    List<TxModel> out = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      TradeRecord trade = trades.next();
      out.add(gen.next(trade));
    }
    return out;
  }

  public static byte[] document(List<TxModel> records) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    StaxRecordWriter writer = new StaxRecordWriter(bytes);
    writer.startDocument("msg-0001", 1_700_000_000_123_456L);
    for (TxModel record : records) {
      writer.writeTx(record);
    }
    writer.endDocument();
    return bytes.toByteArray();
  }
}
