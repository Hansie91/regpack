package dev.regpack.spikes.x.xml;

import dev.regpack.bench.data.SplitMix64;
import dev.regpack.bench.data.TradeRecord;
import dev.regpack.spikes.x.schema.SchemaStats;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps a {@link TradeRecord} onto the 65 leaf fields of the synthetic schema; what the trade does
 * not carry comes from a {@link SplitMix64} stream of the seed, so the same seed and the same
 * trades give the same records on every JVM, operating system, locale and zone. Every value
 * satisfies every facet of the schema; the enumerations are read from the schema file itself.
 *
 * <p>Not thread-safe: one generator per thread.
 */
public final class XmlRecordGen {
  private static final String SCHEMA = "synthetic/txn-1.xsd";
  private static final int MAX_GROUP = 20;

  /** The 65 leaf paths in schema order; {@code XmlRecordGenTest} compares them with the schema. */
  private static final List<String> FIELD_PATHS =
      List.of(
          "marking/f01_record_ref",
          "marking/f02_revision_no",
          "marking/f03_batch_slot",
          "marking/f04_origin_label",
          "buyer_side/f05_party_kind",
          "buyer_side/identity/person/f06_given_name",
          "buyer_side/identity/person/f07_family_name",
          "buyer_side/identity/person/f08_birth_date",
          "buyer_side/identity/organisation/f09_org_code",
          "buyer_side/identity/organisation/f10_org_label",
          "buyer_side/f11_zone",
          "buyer_side/f12_contact_tag",
          "buyer_side/f13_reference_note",
          "seller_side/f14_party_kind",
          "seller_side/identity/person/f15_given_name",
          "seller_side/identity/person/f16_family_name",
          "seller_side/identity/person/f17_birth_date",
          "seller_side/identity/organisation/f18_org_code",
          "seller_side/identity/organisation/f19_org_label",
          "seller_side/f20_zone",
          "seller_side/f21_contact_tag",
          "seller_side/f22_reference_note",
          "transmission/f23_forwarder_label",
          "transmission/f24_forwarded_at",
          "transmission/f25_route_tag",
          "trade_details/f26_trade_time",
          "trade_details/f27_venue",
          "trade_details/price/f28_price_amount",
          "trade_details/price/f29_price_rate",
          "trade_details/price/f30_price_pending",
          "trade_details/f31_quantity",
          "trade_details/f32_side",
          "trade_details/f33_capacity",
          "trade_details/f34_settle_date",
          "trade_details/f35_notional",
          "trade_details/f36_settle_style",
          "trade_details/f37_trade_note",
          "instrument/f38_instrument_ref",
          "instrument/f39_category",
          "instrument/f40_label",
          "instrument/f41_issue_date",
          "instrument/alias/f42_alias_value",
          "instrument/alias/f43_alias_scheme",
          "instrument/f44_listing_flag",
          "instrument/f45_digest_code",
          "instrument/f46_remark",
          "derivative/f47_expiry_date",
          "derivative/f48_strike",
          "derivative/f49_option_style",
          "derivative/underlying/f50_underlying_ref",
          "derivative/underlying/f51_underlying_weight",
          "derivative/f52_delivery_kind",
          "derivative/f53_barrier_level",
          "derivative/f54_contract_ref",
          "decision_maker/f55_decision_role",
          "decision_maker/f56_person_link",
          "decision_maker/f57_decision_label",
          "decision_maker/f58_decision_tag",
          "execution/f59_exec_role",
          "execution/f60_exec_link",
          "execution/f61_exec_label",
          "f62_stance",
          "flags/f63_late_marker",
          "flags/f64_extra_kind",
          "flags/f65_comment");

  private static final Map<String, List<String>> ENUMS = enumerations();
  private static final List<String> ORIGIN = values("origin_kind");
  private static final List<String> PARTY = values("party_kind");
  private static final List<String> ZONE = values("zone_code");
  private static final List<String> UNIT = values("unit_label");
  private static final List<String> VENUE = values("venue_code");
  private static final List<String> SIDE = values("side_marker");
  private static final List<String> CAPACITY = values("capacity_kind");
  private static final List<String> CATEGORY = values("category_code");
  private static final List<String> SETTLE = values("settle_style");
  private static final List<String> OPTION = values("option_style");
  private static final List<String> DELIVERY = values("delivery_kind");
  private static final List<String> DECISION = values("decision_role");
  private static final List<String> EXEC = values("exec_role");
  private static final List<String> STANCE = values("stance_kind");
  private static final List<String> EXTRA = values("extra_kind");
  private static final List<String> MONEY = values("money_code");
  private static final List<String> BASIS = values("basis_kind");
  private static final List<String> GRADE = values("grade_kind");

  private static final List<String> GIVEN =
      List.of(
          "Aldo", "Brea", "Cato", "Dara", "Elio", "Fenn", "Gwen", "Hale", "Ines", "Joss", "Kira",
          "Lund", "Mara", "Nils", "Odo", "Pia", "Quin", "Rhea", "Soren", "Tove", "Zoë", "Renée");
  private static final List<String> FAMILY =
      List.of(
          "Alder", "Brook", "Carr", "Dunn", "Ebbs", "Frost", "Glenn", "Holm", "Ivers", "Jarl",
          "Kemp", "Lowe", "Marsh", "Nash", "Oakes", "Pryce", "Quill", "Rowe", "Stoke", "Thorne",
          "Sjöberg", "O'Neil");
  private static final List<String> COMPANY =
      List.of(
          "North Quay Trading",
          "Harbor & Sons",
          "Blue Mill Works",
          "Kestrel <Group>",
          "Ember Holdings",
          "Larch & Pine Ltd",
          "Copper Field Co",
          "Tidal Ledger Partners",
          "Mossgate Supply",
          "Ninefold Freight");
  private static final List<String> WORDS =
      List.of(
          "amber", "basket", "candle", "dune", "ember", "ferry", "garden", "harbor", "island",
          "juniper", "kettle", "lantern", "meadow", "needle", "orchard", "pebble", "quarry",
          "river", "saddle", "timber", "umber", "valley", "willow", "yarrow");

  private static final String ALNUM = "0123456789abcdefghijklmnopqrstuvwxyz";
  private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz";
  private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final String HEX = "0123456789abcdef";
  private static final String[] LINKAGE = {"alpha", "beta", "gamma", "delta"};

  /** The paths of one repeating group occurrence: {@code [occurrence][field]}. */
  private static final String[][] ALIAS = occurrences("instrument/alias", 41, 42);

  private static final String[][] UNDERLYING = occurrences("derivative/underlying", 49, 50);

  private final SplitMix64 rng;
  private final List<String> names = new ArrayList<>(64);
  private final List<String> values = new ArrayList<>(64);
  private final List<String> attributes = new ArrayList<>(64);

  public XmlRecordGen(long seed) {
    // the constant keeps this stream apart from a TradeRecordGen stream of the same seed
    this.rng = new SplitMix64(seed ^ 0x5851F42D4C957F2DL);
  }

  /** The 65 leaf paths in schema order. */
  public static List<String> fieldPaths() {
    return FIELD_PATHS;
  }

  /** One record for one trade; records of the same generator and the same trades are repeatable. */
  public TxModel next(TradeRecord t) {
    names.clear();
    values.clear();
    attributes.clear();
    int assetClass = assetClassIndex(t);
    long tradedMicros = XmlFormat.micros(t.tradedAt());

    // identification
    put(1, ref(t.seq()));
    put(2, Long.toString(t.sourceVersion()));
    if (chance(50)) {
      put(3, "slot" + digits(4));
    }
    if (chance(50)) {
      put(4, pick(ORIGIN));
    }

    // buyer and seller
    party(5, t.clientLei().map(v -> v.toLowerCase(Locale.ROOT)).orElse(""), 94);
    party(14, t.firmLei().toLowerCase(Locale.ROOT), 90);

    // transmission
    if (chance(60)) {
      put(23, label());
      if (chance(70)) {
        put(24, XmlFormat.dateTime(tradedMicros + rng.nextLong(5_000_000L)));
      }
      if (chance(50)) {
        put(25, tag());
      }
    }

    // trade details
    put(26, XmlFormat.dateTime(tradedMicros));
    put(27, VENUE.get(Math.floorMod(t.venueMic().hashCode(), VENUE.size())));
    int price = rng.nextInt(100);
    if (price < 70) {
      put(
          28,
          plain(t.price().setScale(Math.min(t.price().scale(), 5), RoundingMode.DOWN)),
          "currency_code=" + MONEY.get(Math.floorMod(t.currency().hashCode(), MONEY.size())));
    } else if (price < 90) {
      put(
          29,
          plain(BigDecimal.valueOf(1 + rng.nextLong(10_000_000_000L), 8)),
          "rate_basis=" + pick(BASIS));
    } else {
      put(30, rng.chance(1, 2) ? "true" : "false");
    }
    put(31, plain(t.quantity()), "unit_code=" + pick(UNIT));
    put(32, SIDE.get(t.side().equals("BUY") ? 0 : 1));
    put(33, CAPACITY.get(t.capacity().equals("PRINCIPAL") ? 0 : 1));
    if (chance(90)) {
      put(34, t.settlementDate().toString());
    }
    if (chance(40)) {
      put(35, plain(t.price().multiply(t.quantity()).setScale(2, RoundingMode.DOWN)));
    }
    if (chance(40)) {
      put(36, pick(SETTLE));
    }
    if (chance(20)) {
      put(37, note());
    }

    // instrument, with the first repeating group (1 to 7 occurrences)
    put(38, instrumentRef(t.isin()));
    put(39, CATEGORY.get(assetClass * 3 + rng.nextInt(3)));
    if (chance(60)) {
      put(40, label());
    }
    if (chance(50)) {
      put(41, date(2000, 26));
    }
    int aliases = 1 + rng.nextInt(7);
    for (int k = 1; k <= aliases; k++) {
      putPath(ALIAS[k][0], random(LETTERS + "0123456789", 2 + rng.nextInt(11)), "");
      putPath(ALIAS[k][1], code(), "");
    }
    if (chance(40)) {
      put(44, rng.chance(1, 2) ? "true" : "false");
    }
    if (chance(40)) {
      put(45, random(HEX, 32));
    }
    if (chance(20)) {
      put(46, note());
    }

    // derivative fields (in 75% of the records), with the second repeating group (2 to 8
    // occurrences)
    if (chance(75)) {
      put(47, date(2026, 6));
      if (chance(60)) {
        put(
            48,
            plain(BigDecimal.valueOf(1 + rng.nextLong(100_000_000L), 5)),
            "currency_code=" + pick(MONEY));
      }
      if (chance(50)) {
        put(49, pick(OPTION));
      }
      int underlyings = 2 + rng.nextInt(7);
      for (int k = 1; k <= underlyings; k++) {
        String ref =
            k - 1 < t.underlyings().size()
                ? instrumentRef(t.underlyings().get(k - 1))
                : instrumentRef(random(ALNUM, 12));
        putPath(UNDERLYING[k][0], ref, "");
        putPath(
            UNDERLYING[k][1],
            plain(BigDecimal.valueOf(1 + rng.nextLong(99_999_999_999_999_999L), 17)),
            "");
      }
      if (chance(40)) {
        put(52, pick(DELIVERY));
      }
      if (chance(30)) {
        put(53, plain(BigDecimal.valueOf(1 + rng.nextLong(1_000_000_000_000L), 9)));
      }
      if (chance(40)) {
        put(54, code(), chance(50) ? "version_label=" + version() : "");
      }
    }

    // decision maker
    if (chance(70)) {
      put(55, pick(DECISION));
      if (chance(50)) {
        put(56, linkage());
      }
      if (chance(50)) {
        put(57, given() + " " + family());
      }
      if (chance(30)) {
        put(58, label(), chance(50) ? "source_tag=" + tag() : "");
      }
    }

    // execution
    if (chance(60)) {
      put(59, pick(EXEC));
      if (chance(50)) {
        put(60, linkage());
      }
      if (chance(40)) {
        put(61, note(), chance(50) ? "grade_mark=" + pick(GRADE) : "");
      }
    }

    if (chance(50)) {
      put(62, pick(STANCE));
    }

    // other flags
    if (chance(40)) {
      if (chance(60)) {
        put(63, rng.chance(1, 2) ? "true" : "false");
      }
      if (chance(60)) {
        put(64, pick(EXTRA));
      }
      if (chance(40)) {
        put(65, note());
      }
    }
    return new TxModel(names, values, attributes);
  }

  /**
   * One party block. {@code first} is the number of the party kind field; the person leaves follow
   * it. {@code organisationCode} is used when the party is an organisation; an empty code makes the
   * party a person.
   */
  private void party(int first, String organisationCode, int organisationPercent) {
    put(first, pick(PARTY));
    boolean organisation = !organisationCode.isEmpty() && chance(organisationPercent);
    if (organisation) {
      put(first + 4, organisationCode);
      put(first + 5, company());
    } else {
      put(first + 1, given());
      put(first + 2, family());
      put(first + 3, date(1950, 60));
    }
    if (chance(70)) {
      put(first + 6, pick(ZONE));
    }
    if (chance(40)) {
      put(first + 7, label(), chance(50) ? "source_tag=" + tag() : "");
    }
    if (chance(30)) {
      put(first + 8, note());
    }
  }

  // ---- emitting

  private void put(int number, String value) {
    putPath(FIELD_PATHS.get(number - 1), value, "");
  }

  private void put(int number, String value, String attribute) {
    putPath(FIELD_PATHS.get(number - 1), value, attribute);
  }

  private void putPath(String path, String value, String attribute) {
    names.add(path);
    values.add(value);
    attributes.add(attribute);
  }

  // ---- values

  private boolean chance(int percent) {
    return rng.chance(percent, 100);
  }

  private String pick(List<String> list) {
    return list.get(rng.nextInt(list.size()));
  }

  private String random(String alphabet, int length) {
    StringBuilder out = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      out.append(alphabet.charAt(rng.nextInt(alphabet.length())));
    }
    return out.toString();
  }

  private String digits(int length) {
    return random("0123456789", length);
  }

  /** {@code [a-z]{3}-[0-9]{6}-[a-z]{2}}. */
  private String ref(long seq) {
    String number = Long.toString(Math.floorMod(seq, 1_000_000L));
    StringBuilder out = new StringBuilder(14).append(random(LETTERS, 3)).append('-');
    for (int i = number.length(); i < 6; i++) {
      out.append('0');
    }
    return out.append(number).append('-').append(random(LETTERS, 2)).toString();
  }

  /** {@code q[a-z]{3}-[0-9]{4}-[a-z0-9]{3}}, derived from the characters of a code. */
  private static String instrumentRef(String source) {
    String s = source.toLowerCase(Locale.ROOT);
    StringBuilder out = new StringBuilder(13).append('q');
    for (int i = 0; i < 3; i++) {
      out.append((char) ('a' + Math.floorMod(s.charAt(2 + i), 26)));
    }
    out.append('-');
    for (int i = 0; i < 4; i++) {
      out.append((char) ('0' + Math.floorMod(s.charAt(5 + i), 10)));
    }
    out.append('-');
    for (int i = 0; i < 3; i++) {
      out.append(ALNUM.charAt(Math.floorMod(s.charAt(9 + i), 36)));
    }
    return out.toString();
  }

  /** {@code [a-z][a-z0-9_]{2,15}}. */
  private String tag() {
    return random(LETTERS, 1) + random(ALNUM + "_", 2 + rng.nextInt(14));
  }

  /** {@code [A-Z]{2}[0-9]{3}}. */
  private String code() {
    return random(UPPER, 2) + digits(3);
  }

  /** {@code v[0-9]{1,3}(\.[0-9]{1,3}){0,2}}. */
  private String version() {
    StringBuilder out = new StringBuilder("v").append(1 + rng.nextInt(99));
    for (int i = rng.nextInt(3); i > 0; i--) {
      out.append('.').append(rng.nextInt(100));
    }
    return out.toString();
  }

  /** {@code (alpha|beta|gamma|delta)-[0-9]{4,8}}. */
  private String linkage() {
    return LINKAGE[rng.nextInt(LINKAGE.length)] + "-" + digits(4 + rng.nextInt(5));
  }

  private String given() {
    return pick(GIVEN);
  }

  private String family() {
    return pick(FAMILY);
  }

  private String company() {
    return pick(COMPANY);
  }

  /** At most 35 characters. */
  private String label() {
    return switch (rng.nextInt(3)) {
      case 0 -> company();
      case 1 -> pick(WORDS) + " " + pick(WORDS);
      default -> given() + " " + family();
    };
  }

  /** 20 to 120 characters, at most 140. */
  private String note() {
    StringBuilder out = new StringBuilder(128);
    int target = 20 + rng.nextInt(101);
    while (out.length() < target) {
      if (out.length() > 0) {
        out.append(' ');
      }
      out.append(pick(WORDS));
    }
    return out.length() > 140 ? out.substring(0, 140) : out.toString();
  }

  private String date(int firstYear, int years) {
    return LocalDate.of(firstYear + rng.nextInt(years), 1 + rng.nextInt(12), 1 + rng.nextInt(28))
        .toString();
  }

  private static String plain(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  // ---- schema-derived tables

  private static List<String> values(String type) {
    List<String> out = ENUMS.get(type);
    if (out == null) {
      throw new IllegalStateException("the schema has no enumeration " + type);
    }
    return out;
  }

  private static Map<String, List<String>> enumerations() {
    try {
      return SchemaStats.enumerationsOfResource(SCHEMA);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@code [k]} holds the two leaf paths of occurrence k (1 to 20) of a group. */
  private static String[][] occurrences(String group, int firstIndex, int secondIndex) {
    String[][] out = new String[MAX_GROUP + 1][];
    for (int k = 1; k <= MAX_GROUP; k++) {
      out[k] =
          new String[] {
            withOccurrence(FIELD_PATHS.get(firstIndex), group, k),
            withOccurrence(FIELD_PATHS.get(secondIndex), group, k)
          };
    }
    return out;
  }

  private static String withOccurrence(String path, String group, int k) {
    if (!path.startsWith(group + "/")) {
      throw new IllegalStateException(path + " is not in the group " + group);
    }
    return group + "#" + k + path.substring(group.length());
  }

  /** The asset class of a trade as an index (equity, debt, derivative, other). */
  private static int assetClassIndex(TradeRecord t) {
    return switch (t.assetClass()) {
      case "EQUITY" -> 0;
      case "DEBT" -> 1;
      case "DERIVATIVE" -> 2;
      default -> 3;
    };
  }
}
