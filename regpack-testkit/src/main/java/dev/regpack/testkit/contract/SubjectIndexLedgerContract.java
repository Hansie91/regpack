package dev.regpack.testkit.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.crypto.SubjectHash;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.SubjectIndexLedger;
import dev.regpack.ports.ledger.SubjectIndexLedger.RefKind;
import dev.regpack.ports.ledger.SubjectIndexLedger.SubjectEntry;
import dev.regpack.ports.ledger.SubjectIndexLedger.SubjectRef;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Contract of the data-subject index of a {@link LedgerStore} (spec 11.6; P3c1 A9, D4, D-K5 and D6
 * of the V003 document): rows of (keyed hash, kind, reference), added idempotently and all or
 * nothing, listed by hash in the order (kind, reference) with strict paging, deleted by reference.
 *
 * <p>Every order below is a hand-written literal in code point order (the order of the UTF-8
 * bytes), never the order an adapter happens to hold the rows in: on PostgreSQL a column with the
 * default collation would fail it (D6).
 */
public interface SubjectIndexLedgerContract {
  /** A fresh, empty ledger for each test. */
  LedgerStore ledger();

  private SubjectIndexLedger index() {
    return ledger();
  }

  /** A subject hash of 64 lowercase hex digits that differs for each {@code n}. */
  private static SubjectHash hash(int n) {
    String digits = Integer.toHexString(n);
    return new SubjectHash("0".repeat(64 - digits.length()) + digits);
  }

  private static SubjectRef report(String ref) {
    return new SubjectRef(RefKind.REPORT_VERSION, ref);
  }

  private static SubjectRef object(String ref) {
    return new SubjectRef(RefKind.EVIDENCE_OBJECT, ref);
  }

  /** An object reference {@code r/} followed by one code point, built without a source escape. */
  private static SubjectRef objectEndingIn(int codePoint) {
    return object("r/" + Character.toString(codePoint));
  }

  private static SubjectEntry entry(int hash, SubjectRef ref) {
    return new SubjectEntry(hash(hash), ref);
  }

  private List<SubjectRef> refsOf(int hash) {
    return index().subjectRefs(hash(hash), Optional.empty(), 1_000);
  }

  /** The pages of {@code subjectRefs} with {@code limit}, walked with the last reference. */
  private List<List<SubjectRef>> walk(int hash, int limit) {
    List<List<SubjectRef>> pages = new ArrayList<>();
    Optional<SubjectRef> after = Optional.empty();
    for (int guard = 0; guard < 5_000; guard++) {
      List<SubjectRef> page = index().subjectRefs(hash(hash), after, limit);
      if (page.isEmpty()) {
        return pages;
      }
      pages.add(page);
      after = Optional.of(page.get(page.size() - 1));
    }
    throw new AssertionError("the walk of subjectRefs does not end");
  }

  private static List<SubjectRef> flatten(List<List<SubjectRef>> pages) {
    List<SubjectRef> all = new ArrayList<>();
    pages.forEach(all::addAll);
    return all;
  }

  @Test
  default void anIndexedRefIsFoundByItsHash() {
    index()
        .indexSubjects(
            List.of(
                entry(1, object("sealed/2026/a")),
                entry(1, report("LEI1|XPAR|E1")),
                entry(2, object("sealed/2026/a"))));

    assertThat(refsOf(1)).containsExactly(report("LEI1|XPAR|E1"), object("sealed/2026/a"));
    assertThat(refsOf(2)).containsExactly(object("sealed/2026/a"));
    assertThat(refsOf(3)).isEmpty();
    // an empty list does nothing
    index().indexSubjects(List.of());
    assertThat(refsOf(1)).hasSize(2);
  }

  @Test
  default void indexingTheSameEntryTwiceIsOneRow() {
    SubjectEntry once = entry(1, object("sealed/2026/a"));
    index().indexSubjects(List.of(once));
    index().indexSubjects(List.of(once));
    index().indexSubjects(List.of(once, entry(1, object("sealed/2026/b")), once));

    assertThat(refsOf(1)).containsExactly(object("sealed/2026/a"), object("sealed/2026/b"));
    // one row per (hash, kind, reference): the removal counts the rows, and it counts one
    assertThat(index().removeSubjectRefs(object("sealed/2026/a"))).isEqualTo(1);
    assertThat(refsOf(1)).containsExactly(object("sealed/2026/b"));
  }

  @Test
  default void walkingThePagesWithAfterReturnsAllRefsOnceAndInOrder() {
    // 2,500 references of one hash, written in descending order and in three calls
    List<SubjectEntry> descending = new ArrayList<>();
    List<SubjectRef> ascending = new ArrayList<>();
    for (int i = 0; i < 2_500; i++) {
      SubjectRef ref = object(String.format(Locale.ROOT, "walk/%06d", i));
      ascending.add(ref);
      descending.add(0, entry(1, ref));
    }
    index().indexSubjects(descending.subList(0, 1_000));
    index().indexSubjects(descending.subList(1_000, 2_000));
    index().indexSubjects(descending.subList(2_000, 2_500));
    // another hash holds some of the same references and must not appear in the walk
    index().indexSubjects(List.of(entry(2, object("walk/000000")), entry(2, object("walk/9"))));

    // a limit above the cap returns the cap; the pages of the cap are 1,000, 1,000 and 500
    assertThat(index().subjectRefs(hash(1), Optional.empty(), 5_000)).hasSize(1_000);
    List<List<SubjectRef>> pages = walk(1, 5_000);
    assertThat(pages).extracting(List::size).containsExactly(1_000, 1_000, 500);
    assertThat(flatten(pages)).containsExactlyElementsOf(ascending);
    // smaller pages give the same sequence, each reference once
    List<List<SubjectRef>> small = walk(1, 999);
    assertThat(small).extracting(List::size).containsExactly(999, 999, 502);
    assertThat(flatten(small)).containsExactlyElementsOf(ascending);
    // "after" is strict, and a reference that is not stored can be the bound
    assertThat(index().subjectRefs(hash(1), Optional.of(object("walk/000009")), 2))
        .containsExactly(object("walk/000010"), object("walk/000011"));
    assertThat(index().subjectRefs(hash(1), Optional.of(object("walk/0000095")), 2))
        .containsExactly(object("walk/000010"), object("walk/000011"));
    assertThat(index().subjectRefs(hash(1), Optional.of(object("walk/002499")), 2)).isEmpty();
    // the hash that was not walked still holds exactly its own rows
    assertThat(refsOf(2)).containsExactly(object("walk/000000"), object("walk/9"));
  }

  @Test
  default void refsAreOrderedByKindThenByByteOrderOfTheRefNotByCollation() {
    // Byte order of the alphabet of an evidence key: '+' ',' '-' '.' digits '=' upper case '_'
    // lower case. A linguistic collation puts r/a before r/A and r/a_b before r/a1, and fails this.
    List<SubjectEntry> entries = new ArrayList<>();
    for (String ref :
        List.of(
            "r/ab", "r/a_b", "r/a=b", "r/a1", "r/a.b", "r/a-b", "r/a,b", "r/a+b", "r/a", "r/B",
            "r/A")) {
      entries.add(entry(1, object(ref)));
    }
    // two report-version references whose text sorts after every object reference above
    entries.add(entry(1, report("z/b")));
    entries.add(entry(1, report("z/A")));
    index().indexSubjects(entries);

    List<SubjectRef> expected =
        List.of(
            report("z/A"),
            report("z/b"),
            object("r/A"),
            object("r/B"),
            object("r/a"),
            object("r/a+b"),
            object("r/a,b"),
            object("r/a-b"),
            object("r/a.b"),
            object("r/a1"),
            object("r/a=b"),
            object("r/a_b"),
            object("r/ab"));
    assertThat(refsOf(1)).containsExactlyElementsOf(expected);
    // the same sequence page by page, with the bound in the middle of the characters
    assertThat(flatten(walk(1, 3))).containsExactlyElementsOf(expected);
    assertThat(flatten(walk(1, 1))).containsExactlyElementsOf(expected);
    assertThat(index().subjectRefs(hash(1), Optional.of(object("r/a")), 3))
        .containsExactly(object("r/a+b"), object("r/a,b"), object("r/a-b"));
    assertThat(index().subjectRefs(hash(1), Optional.of(object("r/a.")), 2))
        .containsExactly(object("r/a.b"), object("r/a1"));
    // the kind comes first: after the last report version the objects follow, whatever the text
    assertThat(index().subjectRefs(hash(1), Optional.of(report("zzz")), 2))
        .containsExactly(object("r/A"), object("r/B"));
  }

  /**
   * Beyond the alphabet of an evidence key the order of UTF-16 code units and the order of code
   * points (the UTF-8 bytes, and {@code COLLATE "C"} in a UTF8 database) differ: a supplementary
   * character comes after U+E000 to U+FFFF by code point and before it by code unit. The index
   * orders by code point whatever the adapter holds. No noncharacter is used, so the test also runs
   * on a server.
   */
  @Test
  default void refsBeyondTheKeyAlphabetAreOrderedByCodePointNotByUtf16Unit() {
    List<SubjectRef> expected =
        List.of(
            object("r/z"),
            objectEndingIn(0x00E9),
            objectEndingIn(0x0800),
            objectEndingIn(0xE000),
            objectEndingIn(0xFFFD),
            objectEndingIn(0x1F600),
            objectEndingIn(0x20000));
    List<SubjectEntry> reversed = new ArrayList<>();
    for (int i = expected.size() - 1; i >= 0; i--) {
      reversed.add(entry(1, expected.get(i)));
    }
    index().indexSubjects(reversed);

    assertThat(refsOf(1)).containsExactlyElementsOf(expected);
    assertThat(flatten(walk(1, 1))).containsExactlyElementsOf(expected);
    assertThat(flatten(walk(1, 3))).containsExactlyElementsOf(expected);
    // the bound is a character below the supplementary ones
    assertThat(index().subjectRefs(hash(1), Optional.of(objectEndingIn(0xFFFD)), 5))
        .containsExactly(objectEndingIn(0x1F600), objectEndingIn(0x20000));
    // a supplementary reference is removed by its own text
    assertThat(index().removeSubjectRefs(objectEndingIn(0x1F600))).isEqualTo(1);
    assertThat(refsOf(1))
        .containsExactly(
            object("r/z"),
            objectEndingIn(0x00E9),
            objectEndingIn(0x0800),
            objectEndingIn(0xE000),
            objectEndingIn(0xFFFD),
            objectEndingIn(0x20000));
  }

  @Test
  default void theSameTextUnderTheOtherKindIsAnotherRow() {
    index()
        .indexSubjects(
            List.of(
                entry(1, report("same/text")),
                entry(1, object("same/text")),
                entry(2, object("same/text"))));

    assertThat(refsOf(1)).containsExactly(report("same/text"), object("same/text"));
    assertThat(index().removeSubjectRefs(report("same/text"))).isEqualTo(1);
    assertThat(refsOf(1)).containsExactly(object("same/text"));
    assertThat(refsOf(2)).containsExactly(object("same/text"));
  }

  @Test
  default void removeDeletesOnlyTheNamedRef() {
    index()
        .indexSubjects(
            List.of(
                entry(1, object("sealed/a")),
                entry(1, object("sealed/b")),
                entry(1, object("sealed/c")),
                entry(2, object("sealed/a")),
                entry(2, object("sealed/b")),
                entry(3, object("sealed/c"))));

    // two hashes name the reference: both rows go, the count is the number of rows
    assertThat(index().removeSubjectRefs(object("sealed/a"))).isEqualTo(2);
    assertThat(refsOf(1)).containsExactly(object("sealed/b"), object("sealed/c"));
    assertThat(refsOf(2)).containsExactly(object("sealed/b"));
    assertThat(refsOf(3)).containsExactly(object("sealed/c"));
    // the removal is idempotent
    assertThat(index().removeSubjectRefs(object("sealed/a"))).isZero();
    assertThat(index().removeSubjectRefs(object("sealed/c"))).isEqualTo(2);
    assertThat(refsOf(1)).containsExactly(object("sealed/b"));
    assertThat(refsOf(2)).containsExactly(object("sealed/b"));
    assertThat(refsOf(3)).isEmpty();
    // a removed reference can be indexed again
    index().indexSubjects(List.of(entry(1, object("sealed/a"))));
    assertThat(refsOf(1)).containsExactly(object("sealed/a"), object("sealed/b"));
  }

  @Test
  default void removeOfAnUnknownRefReturnsZero() {
    assertThat(index().removeSubjectRefs(object("sealed/none"))).isZero();
    index().indexSubjects(List.of(entry(1, object("sealed/a"))));
    assertThat(index().removeSubjectRefs(object("sealed/none"))).isZero();
    // a prefix, a longer text and the other kind are not the named reference
    assertThat(index().removeSubjectRefs(object("sealed/"))).isZero();
    assertThat(index().removeSubjectRefs(object("sealed/ab"))).isZero();
    assertThat(index().removeSubjectRefs(report("sealed/a"))).isZero();
    assertThat(refsOf(1)).containsExactly(object("sealed/a"));
  }

  @Test
  default void aRefOf512CharactersIsStored() {
    String longest = "k".repeat(511) + "z";
    assertThat(longest).hasSize(512);
    SubjectRef ref = object(longest);
    index().indexSubjects(List.of(new SubjectEntry(hash(1), ref)));

    assertThat(refsOf(1)).containsExactly(ref);
    // the neighbours in order are told apart at the last character
    assertThat(index().subjectRefs(hash(1), Optional.of(object("k".repeat(511) + "y")), 5))
        .containsExactly(ref);
    assertThat(index().subjectRefs(hash(1), Optional.of(ref), 5)).isEmpty();
    assertThat(index().removeSubjectRefs(ref)).isEqualTo(1);
    assertThat(refsOf(1)).isEmpty();
  }

  /**
   * Pins the validation of the record that every adapter inherits: the ledger never receives the
   * value, so the assertions that nothing was written hold by construction.
   */
  @Test
  default void aRefWithAControlCharacterOrOver512IsRefused() {
    index().indexSubjects(List.of(entry(1, object("sealed/a"))));
    List<String> refused =
        List.of(
            "",
            "a\u0000b",
            "a\u0001b",
            "a\u001fb",
            "a\u007fb",
            "a\nb",
            "a\tb",
            "k".repeat(513),
            "a\ud800b",
            "a\udc00b",
            "a\ud800");
    for (String bad : refused) {
      for (RefKind kind : RefKind.values()) {
        assertThatThrownBy(() -> new SubjectRef(kind, bad))
            .as("a reference of %d characters", bad.length())
            .isInstanceOf(IllegalArgumentException.class)
            // the message names the rule and never echoes the value (nothing to echo when empty)
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain(bad.isEmpty() ? "?" : bad));
      }
    }
    assertThatThrownBy(() -> new SubjectRef(null, "a")).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SubjectRef(RefKind.EVIDENCE_OBJECT, null))
        .isInstanceOf(NullPointerException.class);
    // a paired surrogate is a valid character and counts as two
    assertThat(new SubjectRef(RefKind.EVIDENCE_OBJECT, "a😀b").ref()).hasSize(4);
    // the index is as it was (the refusals are the record constructor's)
    assertThat(refsOf(1)).containsExactly(object("sealed/a"));
  }

  @Test
  default void aBatchWithABadEntryWritesNothing() {
    index().indexSubjects(List.of(entry(1, object("sealed/stored"))));
    SubjectEntry good = entry(1, object("sealed/new"));

    // a bad value cannot be built; a missing element or list is what a batch can carry
    assertThatThrownBy(() -> index().indexSubjects(Arrays.asList(good, null)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> index().indexSubjects(Arrays.asList(null, good)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> index().indexSubjects(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SubjectEntry(null, object("sealed/x")))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SubjectEntry(hash(1), null))
        .isInstanceOf(NullPointerException.class);
    assertThat(refsOf(1)).containsExactly(object("sealed/stored"));

    // a limit below zero is refused, a limit of zero returns nothing
    assertThatThrownBy(() -> index().subjectRefs(hash(1), Optional.empty(), -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(index().subjectRefs(hash(1), Optional.empty(), 0)).isEmpty();
    // the good entry is stored by a call of its own
    index().indexSubjects(List.of(good));
    assertThat(refsOf(1)).containsExactly(object("sealed/new"), object("sealed/stored"));
  }
}
