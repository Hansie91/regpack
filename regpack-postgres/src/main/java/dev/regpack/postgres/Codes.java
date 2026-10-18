package dev.regpack.postgres;

import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.OutboxLedger.OutboxKind;
import dev.regpack.ports.ledger.OutboxLedger.OutboxState;
import dev.regpack.ports.ledger.ReportLedger.VersionOrigin;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The stable {@code smallint} codes of the enums that the ledger stores as numbers (D-S9). A code
 * is written once and never changes; the table below is explicit, not the ordinal, so reordering or
 * inserting a constant cannot change a stored value. A new enum constant has no code until a task
 * adds one here: {@code of} refuses it, so the adapter fails closed instead of storing a wrong
 * value.
 *
 * <p>The numbers 4, 5 and 11 of {@link DeliveryState} and 1 of {@link OutboxState} are also written
 * into the partial-index predicates of {@code V001__core.sql}; {@code CodesTest} and {@code
 * SchemaDocMatchesMigrationsTest} pin both sides.
 */
public final class Codes {
  private static final Table<Outcome> OUTCOME =
      new Table<>("outcome", Outcome.class)
          .put(Outcome.REPORT, 1)
          .put(Outcome.DO_NOT_REPORT, 2)
          .put(Outcome.REPORT_BY_OTHER, 3)
          .put(Outcome.UNDETERMINED, 4);

  private static final Table<Leg> LEG =
      new Table<>("leg", Leg.class).put(Leg.MARKET, 1).put(Leg.CLIENT, 2).put(Leg.ALLOCATION, 3);

  private static final Table<DeliveryState> DELIVERY_STATE =
      new Table<>("delivery state", DeliveryState.class)
          .put(DeliveryState.PREPARED, 1)
          .put(DeliveryState.BLOCKED, 2)
          .put(DeliveryState.QUEUED, 3)
          .put(DeliveryState.SENDING, 4)
          .put(DeliveryState.AWAITING_ACK, 5)
          .put(DeliveryState.ACCEPTED_PROVISIONAL, 6)
          .put(DeliveryState.ACCEPTED_CONFIRMED, 7)
          .put(DeliveryState.PENDING_AT_ENDPOINT, 8)
          .put(DeliveryState.REJECTED, 9)
          .put(DeliveryState.FILE_REJECTED, 10)
          .put(DeliveryState.IN_DOUBT, 11)
          .put(DeliveryState.RESEND_PENDING, 12)
          .put(DeliveryState.IN_REPAIR, 13)
          .put(DeliveryState.SUPERSEDED, 14)
          .put(DeliveryState.WITHDRAWN, 15)
          .put(DeliveryState.WRITTEN_OFF, 16);

  private static final Table<OutboxKind> OUTBOX_KIND =
      new Table<>("outbox kind", OutboxKind.class)
          .put(OutboxKind.DECIDE_BATCH, 1)
          .put(OutboxKind.SEQUENCE, 2);

  private static final Table<OutboxState> OUTBOX_STATE =
      new Table<>("outbox state", OutboxState.class)
          .put(OutboxState.PENDING, 1)
          .put(OutboxState.DONE, 2);

  private static final Table<VersionOrigin> VERSION_ORIGIN =
      new Table<>("version origin", VersionOrigin.class)
          .put(VersionOrigin.SOURCE, 1)
          .put(VersionOrigin.ARM, 2)
          .put(VersionOrigin.IMPORTED, 3);

  private Codes() {}

  public static short of(Outcome outcome) {
    return OUTCOME.code(outcome);
  }

  public static Outcome outcome(short code) {
    return OUTCOME.constant(code);
  }

  public static short of(Leg leg) {
    return LEG.code(leg);
  }

  public static Leg leg(short code) {
    return LEG.constant(code);
  }

  public static short of(DeliveryState state) {
    return DELIVERY_STATE.code(state);
  }

  public static DeliveryState deliveryState(short code) {
    return DELIVERY_STATE.constant(code);
  }

  public static short of(OutboxKind kind) {
    return OUTBOX_KIND.code(kind);
  }

  public static OutboxKind outboxKind(short code) {
    return OUTBOX_KIND.constant(code);
  }

  public static short of(OutboxState state) {
    return OUTBOX_STATE.code(state);
  }

  public static OutboxState outboxState(short code) {
    return OUTBOX_STATE.constant(code);
  }

  public static short of(VersionOrigin origin) {
    return VERSION_ORIGIN.code(origin);
  }

  public static VersionOrigin versionOrigin(short code) {
    return VERSION_ORIGIN.constant(code);
  }

  /** One enum's explicit code table; built once in the static initializer, read-only after. */
  private static final class Table<E extends Enum<E>> {
    private final String what;
    private final Map<E, Short> codes;
    private final Map<Short, E> constants = new HashMap<>();

    Table(String what, Class<E> type) {
      this.what = what;
      this.codes = new EnumMap<>(type);
    }

    Table<E> put(E constant, int code) {
      if (code <= 0 || code > Short.MAX_VALUE) {
        throw new IllegalStateException(what + " code out of range: " + code);
      }
      short value = (short) code;
      if (codes.put(constant, value) != null || constants.put(value, constant) != null) {
        throw new IllegalStateException(what + " code or constant listed twice: " + constant);
      }
      return this;
    }

    short code(E constant) {
      Objects.requireNonNull(constant, what);
      Short code = codes.get(constant);
      if (code == null) {
        throw new IllegalArgumentException("no stored code for " + what + " " + constant.name());
      }
      return code;
    }

    E constant(short code) {
      E constant = constants.get(code);
      if (constant == null) {
        throw new IllegalArgumentException("unknown " + what + " code: " + code);
      }
      return constant;
    }
  }
}
