package dev.regpack.bench.data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A deterministic generator of synthetic trade events. The same seed gives the same sequence on
 * every JVM, operating system, locale and time zone. The universes (instruments, clients, venues)
 * are the same for every seed; the seed only decides which of them each record draws.
 */
public final class TradeRecordGen {
  static final List<String> EVENT_KINDS =
      List.of("NEW", "AMEND", "CANCEL", "CORRECTION", "NOVATION", "ASSIGNMENT");

  /** Basis points per kind, same order as {@link #EVENT_KINDS}: 90%, 6%, 2%, 1%, 0.5%, 0.5%. */
  private static final int[] EVENT_KIND_BP = {9000, 600, 200, 100, 50, 50};

  static final List<String> ASSET_CLASSES = List.of("EQUITY", "DEBT", "DERIVATIVE", "OTHER");

  /** Basis points per class: 70%, 15%, 10%, 5%. */
  private static final int[] ASSET_CLASS_BP = {7000, 1500, 1000, 500};

  private static final List<String> CFIS = List.of("ESVUFR", "DBFUFR", "FFICSX", "MMMMMM");
  static final List<String> CURRENCY_LIST =
      List.of("EUR", "USD", "GBP", "CHF", "SEK", "DKK", "NOK", "JPY");
  private static final Set<String> CURRENCIES = Set.copyOf(CURRENCY_LIST);
  static final List<String> CAPACITIES = List.of("PRINCIPAL", "AGENCY");
  static final List<String> VENUES = venues();

  private static final List<String> FIRM_LEIS =
      List.of("SYNFIRM0000000000001", "SYNFIRM0000000000002", "SYNFIRM0000000000003");

  static final int INSTRUMENT_COUNT = 200_000;
  static final int CLIENT_COUNT = 20_000;
  private static final String BASE36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final long[] POW10 = pow10();

  private static final Zipf VENUE_ZIPF = new Zipf(VENUES.size(), 1.0);
  private static final Zipf INSTRUMENT_ZIPF = new Zipf(INSTRUMENT_COUNT, 1.0);
  private static final Zipf CLIENT_ZIPF = new Zipf(CLIENT_COUNT, 0.9);
  private static final Zipf CURRENCY_ZIPF = new Zipf(CURRENCY_LIST.size(), 1.0);
  private static final String[] ISINS = isins();
  private static final String[] CLIENT_LEIS = clientLeis();

  /** The one business day of the corpus. */
  private static final LocalDate TRADE_DATE = LocalDate.of(2026, 10, 9);

  private static final long DAY_START_SECONDS = TRADE_DATE.toEpochDay() * 86_400L;
  private static final long SESSION_START_MICROS = 7L * 3_600L * 1_000_000L;
  private static final long SESSION_LENGTH_MICROS = 10L * 3_600L * 1_000_000L;

  private final SplitMix64 rng;
  private long sequence;

  public TradeRecordGen(long seed) {
    this.rng = new SplitMix64(seed);
  }

  /** The 8 currencies that are ever generated. */
  public static Set<String> currencies() {
    return CURRENCIES;
  }

  public TradeRecord next() {
    long seq = ++sequence;
    int kindIndex = weighted(EVENT_KIND_BP);
    String eventKind = EVENT_KINDS.get(kindIndex);
    int classIndex = weighted(ASSET_CLASS_BP);
    String assetClass = ASSET_CLASSES.get(classIndex);
    String firmLei = FIRM_LEIS.get(rng.nextInt(FIRM_LEIS.size()));
    String venueMic = VENUES.get(VENUE_ZIPF.sample(rng));
    String isin = ISINS[INSTRUMENT_ZIPF.sample(rng)];
    int clientIndex = CLIENT_ZIPF.sample(rng);
    boolean naturalPerson = rng.chance(3, 100);
    String currency = CURRENCY_LIST.get(CURRENCY_ZIPF.sample(rng));
    BigDecimal price = decimal(3);
    BigDecimal quantity = decimal(4);
    String side = rng.chance(1, 2) ? "BUY" : "SELL";
    String capacity = CAPACITIES.get(rng.nextInt(CAPACITIES.size()));
    Optional<String> traderId =
        rng.chance(4, 5) ? Optional.of("TRD" + pad(rng.nextInt(500), 3)) : Optional.empty();
    long micros = SESSION_START_MICROS + rng.nextLong(SESSION_LENGTH_MICROS);
    Instant tradedAt =
        Instant.ofEpochSecond(
            DAY_START_SECONDS + micros / 1_000_000L, (micros % 1_000_000L) * 1_000L);
    int settlementDraw = rng.nextInt(100);
    int settlementDays =
        settlementDraw < 5 ? 0 : settlementDraw < 15 ? 1 : settlementDraw < 95 ? 2 : 3;
    boolean tradedOnVenue = rng.chance(classIndex == 0 ? 90 : 40, 100);
    List<String> underlyings = new ArrayList<>();
    if (classIndex == 2) {
      int count = 1 + rng.nextInt(3);
      for (int i = 0; i < count; i++) {
        underlyings.add(ISINS[INSTRUMENT_ZIPF.sample(rng)]);
      }
    }
    long sourceVersion = kindIndex == 0 ? 1L : 2L + rng.nextInt(3);
    return new TradeRecord(
        seq,
        "EX-" + Long.toHexString(seq),
        sourceVersion,
        firmLei,
        venueMic,
        eventKind,
        tradedAt,
        TRADE_DATE,
        TRADE_DATE.plusDays(settlementDays),
        assetClass,
        isin,
        CFIS.get(classIndex),
        tradedOnVenue,
        naturalPerson ? "NATURAL_PERSON" : "LEGAL_ENTITY",
        naturalPerson ? Optional.empty() : Optional.of(CLIENT_LEIS[clientIndex]),
        quantity,
        price,
        currency,
        side,
        capacity,
        traderId,
        underlyings);
  }

  private int weighted(int[] basisPoints) {
    int draw = rng.nextInt(10_000);
    int cumulative = 0;
    for (int i = 0; i < basisPoints.length; i++) {
      cumulative += basisPoints[i];
      if (draw < cumulative) {
        return i;
      }
    }
    throw new IllegalStateException("weights must add up to 10000");
  }

  /** A positive decimal with scale 2 to 6 and {@code integerDigits} digits before the point. */
  private BigDecimal decimal(int integerDigits) {
    int scale = 2 + rng.nextInt(5);
    return BigDecimal.valueOf(1 + rng.nextLong(POW10[scale + integerDigits]), scale);
  }

  private static String pad(int value, int width) {
    String digits = Integer.toString(value);
    StringBuilder out = new StringBuilder(width);
    for (int i = digits.length(); i < width; i++) {
      out.append('0');
    }
    return out.append(digits).toString();
  }

  private static long[] pow10() {
    long[] table = new long[19];
    table[0] = 1L;
    for (int i = 1; i < table.length; i++) {
      table[i] = table[i - 1] * 10L;
    }
    return table;
  }

  /** 40 synthetic venue codes; the first three are the ones the rule templates mention. */
  private static List<String> venues() {
    List<String> out = new ArrayList<>();
    out.add("XPAR");
    out.add("XAMS");
    out.add("XBRU");
    for (int i = 3; i < 40; i++) {
      out.add("SY" + (char) ('A' + i / 26) + (char) ('A' + i % 26));
    }
    return List.copyOf(out);
  }

  private static String[] isins() {
    SplitMix64 r = new SplitMix64(0x15121512L);
    String[] out = new String[INSTRUMENT_COUNT];
    for (int i = 0; i < out.length; i++) {
      StringBuilder sb = new StringBuilder(12).append("ZZ");
      for (int c = 0; c < 10; c++) {
        sb.append(BASE36.charAt(r.nextInt(36)));
      }
      out[i] = sb.toString();
    }
    return out;
  }

  private static String[] clientLeis() {
    SplitMix64 r = new SplitMix64(0x1EAF1EAFL);
    String[] out = new String[CLIENT_COUNT];
    for (int i = 0; i < out.length; i++) {
      StringBuilder sb = new StringBuilder(20).append(i % 4 == 0 ? 'K' : 'C');
      for (int c = 0; c < 19; c++) {
        sb.append(BASE36.charAt(r.nextInt(36)));
      }
      out[i] = sb.toString();
    }
    return out;
  }
}
