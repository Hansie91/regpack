package dev.regpack.ports.evidence.journal;

import dev.regpack.model.Sha256;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Valid objects of the seven journal types, with fixed values (the goldens use them). */
final class JournalFixtures {
  static final String SCOPE_TEXT = "PRODUCTION,ARM-ONE,5493001KJTIIGC8Y1R12,FCA";
  static final JournalScope SCOPE =
      JournalScope.of(new EndpointKey("PRODUCTION", "ARM-ONE", "5493001KJTIIGC8Y1R12", "FCA"));
  static final Instant AT = Instant.parse("2026-10-19T12:00:00.123456Z");
  static final Instant OBSERVED = Instant.parse("2026-10-19T12:00:01.500000Z");

  private JournalFixtures() {}

  static String hash(String seed) {
    return Sha256.ofUtf8(seed).hex();
  }

  /** A valid version 1 payload of the type, as a class of that type can carry it. */
  static Map<String, Object> payload(JournalType type, JournalClass journalClass) {
    Map<String, Object> p = new LinkedHashMap<>();
    switch (type) {
      case DELIVERY_INTENT -> {
        p.put("attempt_id", 42L);
        p.put("delivery_id", 17L);
        p.put("business_key", "TRADE-1");
        p.put("trn", "TRN0000000000000000001");
        p.put("version_seq", 2L);
        p.put("action", "NEWT");
        p.put("logical_hash", hash("logical"));
        p.put("artifact_sha256", hash("artifact"));
        p.put("file_name", "report-1.xml");
        p.put("context_ids", List.of(hash("context-1"), hash("context-2")));
      }
      case BATCH_ADMITTED -> {
        p.put("batch_id", "batch:7");
        p.put("pack_digest", hash("pack"));
        p.put("context_digests", List.of(hash("context-1")));
      }
      case TRANSPORT_RESULT -> {
        p.put("attempt_id", 42L);
        p.put("status", "ACCEPTED");
        p.put("detail", "http 202");
        p.put("observed_at", OBSERVED);
      }
      case FEEDBACK_RECEIVED -> {
        p.put("feedback_sha256", hash("feedback"));
        p.put("received_at", OBSERVED);
        p.put("file_ids", List.of("file-1", "file-2"));
      }
      case RECOVERY_DECISION -> {
        p.put("delivery_id", 17L);
        p.put("attempt_id", 42L);
        p.put("intent_restore_epoch", 3L);
        p.put("artifact_sha256", hash("artifact"));
        p.put("decision", "RESEND");
        p.put("authorises_effect", journalClass == JournalClass.A);
        p.put("reason", "no acknowledgement");
      }
      case OPERATOR_ACTION -> {
        p.put("kind", journalClass == JournalClass.A ? "FORCED_RESEND" : "APPROVAL");
        p.put("operator", "op-1");
        p.put("subject", "delivery 17");
        p.put("detail", "approved");
      }
      case VERSION_ASSIGNED -> {
        p.put("batch_id", "batch:7");
        p.put(
            "assignments",
            List.of(
                Map.of("subject", "TRADE-1", "trn", "TRN0000000000000000001", "version_seq", 1L),
                Map.of("subject", "TRADE-2", "trn", "TRN0000000000000000002", "version_seq", 2L)));
      }
    }
    return p;
  }

  /** The golden object of the type: its key is fixed by the type. */
  static JournalObject object(JournalType type) {
    JournalKey key =
        switch (type) {
          case DELIVERY_INTENT -> new JournalKey.ClassA(SCOPE, 3, type, "42", 1);
          case BATCH_ADMITTED ->
              new JournalKey.ClassA(JournalScope.control(), 0, type, "batch:7", 1);
          case TRANSPORT_RESULT -> new JournalKey.ClassB(SCOPE, 3, 10_001);
          case FEEDBACK_RECEIVED -> new JournalKey.ClassB(SCOPE, 3, 10_002);
          case RECOVERY_DECISION -> new JournalKey.ClassA(SCOPE, 3, type, "17", 1);
          case OPERATOR_ACTION -> new JournalKey.ClassA(SCOPE, 3, type, "op-1", 1);
          case VERSION_ASSIGNED -> new JournalKey.ClassB(SCOPE, 3, 10_003);
        };
    return new JournalObject(key, type, 1, AT, payload(type, key.journalClass()));
  }
}
