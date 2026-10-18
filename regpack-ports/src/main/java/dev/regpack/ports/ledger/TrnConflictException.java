package dev.regpack.ports.ledger;

/**
 * Thrown by {@link ReportLedger#commitReportable} when the pair (executing LEI, TRN) belongs to
 * another report subject (spec 10: {@code UNIQUE (executing LEI, TRN)}). Nothing was written.
 */
public class TrnConflictException extends LedgerException {
  private static final long serialVersionUID = 1L;

  private final String executingLei;
  private final String trn;

  public TrnConflictException(String executingLei, String trn) {
    super("the TRN " + trn + " is already assigned under the executing LEI " + executingLei);
    this.executingLei = executingLei;
    this.trn = trn;
  }

  public String executingLei() {
    return executingLei;
  }

  public String trn() {
    return trn;
  }
}
