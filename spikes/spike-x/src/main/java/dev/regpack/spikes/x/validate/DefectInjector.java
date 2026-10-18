package dev.regpack.spikes.x.validate;

import dev.regpack.bench.data.SplitMix64;
import dev.regpack.spikes.x.xml.StaxRecordWriter;
import dev.regpack.spikes.x.xml.TxModel;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns a seeded share of the records into records that the synthetic schema rejects, one of six
 * kinds each. Every kind changes a field that every generated record carries, so that a defect
 * never misses by landing on an optional element that is absent.
 *
 * <p>Not thread-safe: one injector per thread.
 */
public final class DefectInjector {
  /** What is wrong with a defective record. */
  public enum Kind {
    /** A value that does not match the pattern of its type. */
    PATTERN,
    /** A value that is not in the code list of its type. */
    ENUMERATION,
    /** A required element is missing. */
    MISSING_MANDATORY,
    /** A string longer than the maximum length of its type. */
    OVER_LENGTH,
    /** A decimal with more digits than its type allows. */
    TOO_MANY_DIGITS,
    /** Two elements of a sequence in the wrong order. */
    WRONG_ORDER
  }

  private static final long DENOMINATOR = 1_000_000_000L;

  private final SplitMix64 rng;
  private final long numerator;
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
  private final StaxRecordWriter writer = new StaxRecordWriter(bytes);

  /** {@code rate} is the chance that a record is defective, from 0 to 1. */
  public DefectInjector(long seed, double rate) {
    if (!(rate >= 0.0 && rate <= 1.0)) {
      throw new IllegalArgumentException("rate must be from 0 to 1: " + rate);
    }
    this.rng = new SplitMix64(seed ^ 0x2545F4914F6CDD1DL);
    this.numerator = Math.round(rate * DENOMINATOR);
  }

  /**
   * Decides, with the seeded stream, whether this record is defective and stores its bytes in
   * {@code serialisedOut[0]}: the corrupted record when a kind is returned, the record as it is
   * otherwise. The bytes are one record element that declares the namespace of the schema.
   */
  public Optional<Kind> maybeDefect(TxModel model, byte[][] serialisedOut) {
    Objects.requireNonNull(model, "model");
    if (serialisedOut.length < 1) {
      throw new IllegalArgumentException("serialisedOut needs room for one record");
    }
    if (rng.chance(numerator, DENOMINATOR)) {
      Kind kind = Kind.values()[rng.nextInt(Kind.values().length)];
      int variant = rng.nextInt(1_000);
      serialisedOut[0] = serialiseWith(corrupt(model, kind, variant));
      return Optional.of(kind);
    }
    serialisedOut[0] = serialiseWith(model);
    return Optional.empty();
  }

  private byte[] serialiseWith(TxModel model) {
    bytes.reset();
    writer.writeTx(model);
    writer.flush();
    return bytes.toByteArray();
  }

  /** The bytes of one record element that declares the namespace of the schema. */
  public static byte[] serialise(TxModel model) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
    StaxRecordWriter writer = new StaxRecordWriter(out);
    writer.writeTx(model);
    writer.flush();
    return out.toByteArray();
  }

  /**
   * A copy of the record with one defect of the kind. {@code variant} picks among the places and
   * values that exist for the kind; any integer is valid.
   */
  public static TxModel corrupt(TxModel model, Kind kind, int variant) {
    List<String> names = new ArrayList<>(model.names());
    List<String> values = new ArrayList<>(model.values());
    List<String> attributes = new ArrayList<>(model.attributes());
    int pick = Math.floorMod(variant, 3);
    switch (kind) {
      case PATTERN -> {
        // a reference pattern, an instrument pattern, an alias scheme pattern
        String[] targets = {
          "marking/f01_record_ref",
          "instrument/f38_instrument_ref",
          "instrument/alias/f43_alias_scheme"
        };
        String[] bad = {"BAD_REF", "not-an-instrument", "zz"};
        values.set(indexOf(names, targets[pick]), bad[pick]);
      }
      case ENUMERATION -> {
        String[] targets = {
          "buyer_side/f05_party_kind", "trade_details/f27_venue", "trade_details/f32_side"
        };
        String[] bad = {"unlisted", "venue_not_in_the_list", "sideways"};
        values.set(indexOf(names, targets[pick]), bad[pick]);
      }
      case MISSING_MANDATORY -> {
        String[] targets = {
          "marking/f01_record_ref", "trade_details/f32_side", "buyer_side/f05_party_kind"
        };
        int at = indexOf(names, targets[pick]);
        names.remove(at);
        values.remove(at);
        attributes.remove(at);
      }
      case OVER_LENGTH -> {
        // the alias value allows at most 12 characters
        String[] bad = {
          "thirteen_chars", "this_alias_value_is_far_too_long_for_its_type", "abcdefghijklm"
        };
        values.set(indexOf(names, "instrument/alias/f42_alias_value"), bad[pick]);
      }
      case TOO_MANY_DIGITS -> {
        // the quantity allows 20 digits in all, 6 of them after the point
        String[] bad = {"123456789012345678901", "0.1234567", "99999999999999.9999999"};
        values.set(indexOf(names, "trade_details/f31_quantity"), bad[pick]);
      }
      case WRONG_ORDER -> {
        String[][] pairs = {
          {"trade_details/f26_trade_time", "trade_details/f27_venue"},
          {"trade_details/f32_side", "trade_details/f33_capacity"},
          {"marking/f01_record_ref", "marking/f02_revision_no"}
        };
        int first = indexOf(names, pairs[pick][0]);
        int second = indexOf(names, pairs[pick][1]);
        swap(names, first, second);
        swap(values, first, second);
        swap(attributes, first, second);
      }
    }
    return new TxModel(names, values, attributes);
  }

  private static void swap(List<String> list, int a, int b) {
    String keep = list.get(a);
    list.set(a, list.get(b));
    list.set(b, keep);
  }

  /** The first leaf whose path, without the occurrence numbers, is the target. */
  private static int indexOf(List<String> names, String target) {
    for (int i = 0; i < names.size(); i++) {
      if (withoutOccurrences(names.get(i)).equals(target)) {
        return i;
      }
    }
    throw new IllegalStateException("the record has no field " + target);
  }

  private static String withoutOccurrences(String path) {
    if (path.indexOf('#') < 0) {
      return path;
    }
    StringBuilder out = new StringBuilder(path.length());
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (c == '#') {
        while (i + 1 < path.length() && Character.isDigit(path.charAt(i + 1))) {
          i++;
        }
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
