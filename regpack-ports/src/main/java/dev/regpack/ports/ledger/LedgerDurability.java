package dev.regpack.ports.ledger;

import java.util.Objects;

/**
 * What the ledger knows about the durability of its own commits (spec 11.2: a SENDING commit must
 * survive the loss of the primary before the first byte leaves). The send gate reads it; an adapter
 * that cannot tell answers "not synchronous".
 */
public interface LedgerDurability {
  /**
   * @param synchronous whether a commit is acknowledged only after at least one standby has it
   * @param synchronousStandbys how many standbys take part in that acknowledgement now, at least 0
   * @param detail a fixed, human-readable reason that names no host, credential or business key
   */
  record Status(boolean synchronous, int synchronousStandbys, String detail) {
    public Status {
      Objects.requireNonNull(detail, "detail");
      if (synchronousStandbys < 0) {
        throw new IllegalArgumentException("synchronousStandbys must not be negative");
      }
    }
  }

  /**
   * The durability now. The in-memory ledger answers {@code (false, 0, "in-memory ledger")} unless
   * a test sets it. A status that is {@code synchronous} names at least one standby and every
   * status has a non-blank detail (the contract suite).
   *
   * @throws LedgerException when the ledger cannot be asked; the send gate treats that as closed
   */
  Status durability();
}
