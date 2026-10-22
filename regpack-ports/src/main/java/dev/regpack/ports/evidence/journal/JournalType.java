package dev.regpack.ports.evidence.journal;

import java.util.Objects;

/**
 * The journal object types of spec 11.2 with their wire names. {@code DeliveryIntent} and {@code
 * BatchAdmitted} are class (a) only; {@code TransportResult}, {@code FeedbackReceived} and {@code
 * VersionAssigned} are class (b) only; {@code RecoveryDecision} and {@code OperatorAction} are
 * class (a) when they authorise an external effect and class (b) otherwise.
 *
 * <p>Each type has the most bytes an object of it may have ({@link #maxBytes()}): 64 MiB ({@link
 * JournalObject#MAX_BYTES}) for the two types whose payload is a list that grows with a batch
 * ({@code VersionAssigned}, {@code BatchAdmitted}) and 1 MiB ({@link
 * JournalObject#MAX_SMALL_BYTES}) for every other type.
 */
public enum JournalType {
  DELIVERY_INTENT("DeliveryIntent", true, false, JournalObject.MAX_SMALL_BYTES),
  BATCH_ADMITTED("BatchAdmitted", true, false, JournalObject.MAX_BYTES),
  TRANSPORT_RESULT("TransportResult", false, true, JournalObject.MAX_SMALL_BYTES),
  FEEDBACK_RECEIVED("FeedbackReceived", false, true, JournalObject.MAX_SMALL_BYTES),
  RECOVERY_DECISION("RecoveryDecision", true, true, JournalObject.MAX_SMALL_BYTES),
  OPERATOR_ACTION("OperatorAction", true, true, JournalObject.MAX_SMALL_BYTES),
  VERSION_ASSIGNED("VersionAssigned", false, true, JournalObject.MAX_BYTES);

  private final String wireName;
  private final boolean classA;
  private final boolean classB;
  private final int maxBytes;

  JournalType(String wireName, boolean classA, boolean classB, int maxBytes) {
    this.wireName = wireName;
    this.classA = classA;
    this.classB = classB;
    this.maxBytes = maxBytes;
  }

  public String wireName() {
    return wireName;
  }

  /** The most canonical bytes an object of this type may have; see the type's Javadoc. */
  public int maxBytes() {
    return maxBytes;
  }

  public boolean allows(JournalClass journalClass) {
    return switch (Objects.requireNonNull(journalClass, "journalClass")) {
      case A -> classA;
      case B -> classB;
    };
  }

  /** The type with this wire name; any other text is an {@link IllegalArgumentException}. */
  public static JournalType ofWire(String wire) {
    Objects.requireNonNull(wire, "wire");
    for (JournalType type : values()) {
      if (type.wireName.equals(wire)) {
        return type;
      }
    }
    throw new IllegalArgumentException("unknown journal type name");
  }
}
