package dev.regpack.testkit.ident;

import java.util.List;

/**
 * The synthetic identifiers of the W1a slice (plan V3): valid check digits, no claim that any of
 * them is unassigned. The safety rule is the closed-world reference seeds, not these values. The
 * published examples of the research record are not here and are not in any sample.
 */
public final class SyntheticIds {
  /** The firm's LEI. */
  public static final String FIRM_LEI = "SYNT00FIRM0000000152";

  /** The central counterparty's LEI. */
  public static final String CCP_LEI = "SYNT00CCP00000000194";

  /** The LEI of client 1. */
  public static final String CLIENT1_LEI = "SYNT00CLIENT00000167";

  /** The LEI of client 2. */
  public static final String CLIENT2_LEI = "SYNT00CLIENT00000264";

  /** The LEI of the client whose LEI lapsed. */
  public static final String LAPSED_LEI = "SYNT00LAPSED00000132";

  /** The synthetic ISIN of instrument 1. */
  public static final String ISIN_1 = "ZZ0000000016";

  /** The synthetic ISIN of instrument 2. */
  public static final String ISIN_2 = "ZZ0000000024";

  /** The synthetic ISIN of instrument 3. */
  public static final String ISIN_3 = "ZZ0000000032";

  /** The synthetic ISIN of instrument 4. */
  public static final String ISIN_4 = "ZZ0000000040";

  /** The synthetic ISIN of instrument 5. */
  public static final String ISIN_5 = "ZZ0000000057";

  /** The synthetic ISIN of instrument 6 (the one the instrument seed does not list). */
  public static final String ISIN_6 = "ZZ0000000065";

  /** An ISIN with the right shape and a wrong check digit. */
  public static final String BAD_ISIN = "ZZ0000000017";

  /** The five valid synthetic LEIs. */
  public static final List<String> LEIS =
      List.of(FIRM_LEI, CCP_LEI, CLIENT1_LEI, CLIENT2_LEI, LAPSED_LEI);

  /** The six valid synthetic ISINs. */
  public static final List<String> ISINS = List.of(ISIN_1, ISIN_2, ISIN_3, ISIN_4, ISIN_5, ISIN_6);

  private SyntheticIds() {}
}
