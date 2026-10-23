package dev.regpack.ports.evidence.journal;

import dev.regpack.model.Sha256;
import dev.regpack.ports.evidence.EvidenceKey;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The address of a journal object in the evidence tier (spec 11.2, D-E1).
 *
 * <p>Class (a), keyed by (scope, restore epoch, type, subject, ordinal): {@code
 * journal/{scope}/e{epoch:012d}/a/{wire}/{shard}/{subject}/{ordinal:012d}}. Class (b), keyed by
 * (scope, restore epoch, seq): {@code journal/{scope}/e{epoch:012d}/b/{seq / 10000
 * :08d}/{seq:016d}}. Zero padding makes the text order of a listing the numeric order. {@link
 * #parse} is the inverse of {@link #evidenceKey()}; two different keys never share an {@link
 * EvidenceKey}.
 *
 * <p>The shard keeps a directory small. A numeric subject (an attempt id) is {@code n} and the
 * first eight digits of its padded form, which groups 10,000 consecutive ids. Any other subject (a
 * {@code BatchAdmitted} batch id is {@code <64 hex>:<slice>}, never numeric) is {@code x} and the
 * first byte of the SHA-256 of the subject text in UTF-8, as two lower-case hex digits: 256 shards
 * (for example {@code x3f}), so that 10,000 batches a day do not pile up in one directory. {@link
 * #parse} re-renders the key it parsed and refuses any other shard.
 */
public sealed interface JournalKey permits JournalKey.ClassA, JournalKey.ClassB {
  JournalScope scope();

  long restoreEpoch();

  JournalClass journalClass();

  EvidenceKey evidenceKey();

  /** The key whose {@link #evidenceKey()} is {@code key}; any other key is an IAE. */
  static JournalKey parse(EvidenceKey key) {
    String[] s = Objects.requireNonNull(key, "key").path().split("/", -1);
    if (s.length < 4 || !s[0].equals("journal")) {
      throw notJournalKey();
    }
    JournalScope scope = new JournalScope(s[1]);
    long epoch = digits(s[2], 'e', 12);
    JournalKey parsed;
    if (s[3].equals("a") && s.length == 8) {
      JournalType type = JournalType.ofWire(s[4]);
      parsed = new ClassA(scope, epoch, type, subjectOfPathSegment(s[6]), digits(s[7], '\0', 12));
    } else if (s[3].equals("b") && s.length == 6) {
      parsed = new ClassB(scope, epoch, digits(s[5], '\0', 16));
    } else {
      throw notJournalKey();
    }
    // the key must be exactly what the parsed tuple renders (padding, shard and bucket included)
    if (!parsed.evidenceKey().equals(key)) {
      throw notJournalKey();
    }
    return parsed;
  }

  private static IllegalArgumentException notJournalKey() {
    return new IllegalArgumentException("not a journal key");
  }

  /**
   * The number in {@code text}: an optional prefix character, then exactly {@code width} digits.
   */
  private static long digits(String text, char prefix, int width) {
    int from = prefix == '\0' ? 0 : 1;
    if (text.length() != from + width || (from == 1 && text.charAt(0) != prefix)) {
      throw notJournalKey();
    }
    long value = 0;
    for (int i = from; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < '0' || c > '9') {
        throw notJournalKey();
      }
      value = value * 10 + (c - '0');
    }
    return value;
  }

  private static String subjectOfPathSegment(String segment) {
    String subject = segment.replace('=', ':');
    if (subject.length() == 12 && ClassA.DIGITS.matcher(subject).matches()) {
      int start = 0;
      while (start < subject.length() && subject.charAt(start) == '0') {
        start++;
      }
      if (start == subject.length()) {
        throw notJournalKey();
      }
      return subject.substring(start);
    }
    return subject;
  }

  private static String pad(long value, int width) {
    String digits = Long.toString(value);
    return "0".repeat(width - digits.length()) + digits;
  }

  /** The largest epoch, ordinal or seq: twelve digits. */
  private static void requireNumber(String field, long value, long min) {
    long max = 999_999_999_999L;
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          field + " must be " + min + " to " + max + " (at most 12 digits)");
    }
  }

  /**
   * {@code x} and the first byte of the SHA-256 of the subject text, as two lower-case hex digits.
   */
  private static String hashShard(String subject) {
    return "x" + Sha256.ofUtf8(subject).hex().substring(0, 2);
  }

  /**
   * A class (a) key. The type must allow class (a). The control scope is only for {@code
   * BatchAdmitted} (where it is required) and {@code OperatorAction}. The ordinal is 1 to twelve
   * digits. The subject has the raw alphabet {@code [A-Za-z0-9._:-]{1,100}} (not "." or ".."; '='
   * is not allowed raw and ':' renders as '='); a subject of digits only is 1 to 12 digits with no
   * leading zero and is zero-padded to 12 in the path, so two tuples never share a path.
   */
  record ClassA(
      JournalScope scope, long restoreEpoch, JournalType type, String subjectId, long ordinal)
      implements JournalKey {
    private static final Pattern SUBJECT = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final Pattern NUMERIC_SUBJECT = Pattern.compile("[1-9][0-9]{0,11}");

    public ClassA {
      Objects.requireNonNull(scope, "scope");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(subjectId, "subjectId");
      requireNumber("restoreEpoch", restoreEpoch, 0);
      requireNumber("ordinal", ordinal, 1);
      if (!type.allows(JournalClass.A)) {
        throw new IllegalArgumentException(type.wireName() + " is not a class a journal type");
      }
      if (scope.isControl()) {
        if (type != JournalType.BATCH_ADMITTED && type != JournalType.OPERATOR_ACTION) {
          throw new IllegalArgumentException(
              "the control scope is only for BatchAdmitted and OperatorAction");
        }
      } else if (type == JournalType.BATCH_ADMITTED) {
        throw new IllegalArgumentException("BatchAdmitted needs the control scope");
      }
      if (!SUBJECT.matcher(subjectId).matches()
          || subjectId.equals(".")
          || subjectId.equals("..")) {
        throw new IllegalArgumentException(
            "subjectId must be 1 to 100 characters of [A-Za-z0-9._:-] and not . or ..");
      }
      if (DIGITS.matcher(subjectId).matches() && !NUMERIC_SUBJECT.matcher(subjectId).matches()) {
        throw new IllegalArgumentException(
            "a numeric subjectId must be 1 to 12 digits without a leading zero");
      }
    }

    @Override
    public JournalClass journalClass() {
      return JournalClass.A;
    }

    @Override
    public EvidenceKey evidenceKey() {
      boolean numeric = DIGITS.matcher(subjectId).matches();
      String padded = numeric ? pad(Long.parseLong(subjectId), 12) : subjectId.replace(':', '=');
      String shard = numeric ? "n" + padded.substring(0, 8) : hashShard(subjectId);
      return EvidenceKey.of(
          "journal",
          scope.value(),
          "e" + pad(restoreEpoch, 12),
          "a",
          type.wireName(),
          shard,
          padded,
          pad(ordinal, 12));
    }
  }

  /** A class (b) key: the endpoint scope, the epoch and a sequence number of 1 to twelve digits. */
  record ClassB(JournalScope scope, long restoreEpoch, long seq) implements JournalKey {
    public ClassB {
      Objects.requireNonNull(scope, "scope");
      requireNumber("restoreEpoch", restoreEpoch, 0);
      requireNumber("seq", seq, 1);
      if (scope.isControl()) {
        throw new IllegalArgumentException("a class b object has an endpoint scope");
      }
    }

    @Override
    public JournalClass journalClass() {
      return JournalClass.B;
    }

    @Override
    public EvidenceKey evidenceKey() {
      return EvidenceKey.of(
          "journal",
          scope.value(),
          "e" + pad(restoreEpoch, 12),
          "b",
          pad(seq / 10_000, 8),
          pad(seq, 16));
    }
  }
}
