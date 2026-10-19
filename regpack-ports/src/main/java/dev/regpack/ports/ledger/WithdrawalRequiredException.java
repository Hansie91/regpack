package dev.regpack.ports.ledger;

/**
 * Thrown when a superseding decision is not REPORT, or drops a leg, while the chain has report
 * versions: the withdrawal transaction of spec 9.3 does not exist yet, so the ledger fails closed
 * and writes nothing.
 */
public class WithdrawalRequiredException extends LedgerException {
  private static final long serialVersionUID = 1L;

  public WithdrawalRequiredException(String detail) {
    super("withdrawal required: " + detail);
  }
}
