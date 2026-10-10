package dev.regpack.ports.regime;

import dev.regpack.model.Citation;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Regime;
import dev.regpack.model.canonical.CanonicalEvent;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionContext;
import dev.regpack.ports.exec.CostBudget;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A regime pack as seen by the engine. {@code evaluate} is a pure function of the event, the pinned
 * context and the budget: it never reads a clock ({@code evaluatedAt} is supplied) and does no I/O.
 */
public interface RegimeProvider {
  record Descriptor(Regime regime, String packVersion, String engineMin) {
    public Descriptor {
      Objects.requireNonNull(regime, "regime");
      Objects.requireNonNull(packVersion, "packVersion");
      Objects.requireNonNull(engineMin, "engineMin");
    }
  }

  record EvaluationContext(
      DecisionContext decisionContext,
      IngestKey ingestKey,
      Instant evaluatedAt,
      CostBudget budget) {
    public EvaluationContext {
      Objects.requireNonNull(decisionContext, "decisionContext");
      Objects.requireNonNull(ingestKey, "ingestKey");
      Objects.requireNonNull(evaluatedAt, "evaluatedAt");
      Objects.requireNonNull(budget, "budget");
    }
  }

  record DerivedField(String name, String value, String ruleRef, List<Citation> citations) {
    public DerivedField {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(value, "value");
      Objects.requireNonNull(ruleRef, "ruleRef");
      citations = List.copyOf(citations);
    }
  }

  record Projection(String mediaType, byte[] bytes) {
    public Projection {
      Objects.requireNonNull(mediaType, "mediaType");
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Projection p
          && mediaType.equals(p.mediaType)
          && Arrays.equals(bytes, p.bytes);
    }

    @Override
    public int hashCode() {
      return 31 * mediaType.hashCode() + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
      return "Projection[mediaType=" + mediaType + ", length=" + bytes.length + "]";
    }
  }

  record Result(DecisionBody decision, List<DerivedField> fields, Optional<Projection> projection) {
    public Result {
      Objects.requireNonNull(decision, "decision");
      fields = List.copyOf(fields);
      Objects.requireNonNull(projection, "projection");
    }
  }

  Descriptor describe();

  Result evaluate(CanonicalEvent event, EvaluationContext context);
}
