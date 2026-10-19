package dev.regpack.ports.evidence.journal;

import java.util.Objects;

/**
 * The journal object types of spec 11.2 with their wire names. {@code DeliveryIntent} and {@code
 * BatchAdmitted} are class (a) only; {@code TransportResult}, {@code FeedbackReceived} and {@code
 * VersionAssigned} are class (b) only; {@code RecoveryDecision} and {@code OperatorAction} are
 * class (a) when they authorise an external effect and class (b) otherwise.
 */
public enum JournalType {
  DELIVERY_INTENT("DeliveryIntent", true, false),
  BATCH_ADMITTED("BatchAdmitted", true, false),
  TRANSPORT_RESULT("TransportResult", false, true),
  FEEDBACK_RECEIVED("FeedbackReceived", false, true),
  RECOVERY_DECISION("RecoveryDecision", true, true),
  OPERATOR_ACTION("OperatorAction", true, true),
  VERSION_ASSIGNED("VersionAssigned", false, true);

  private final String wireName;
  private final boolean classA;
  private final boolean classB;

  JournalType(String wireName, boolean classA, boolean classB) {
    this.wireName = wireName;
    this.classA = classA;
    this.classB = classB;
  }

  public String wireName() {
    return wireName;
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
