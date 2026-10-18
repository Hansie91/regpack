package dev.regpack.pack.model;

import dev.regpack.model.Leg;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One report definition of {@code pack.yaml} (BRK-14: its {@code id} is the regime of the
 * decisions; BRK-19: the keys). The path lists name pack files; whether they exist and what they
 * contain is checked by the loader.
 *
 * @param id the regime of the report's decisions
 * @param input path of the canonical input model
 * @param scopeTables paths of the scope tables, in the order they are consulted
 * @param deriveTables paths of derive tables (not interpreted by P2a)
 * @param validate paths of validation files (not interpreted by P2a)
 * @param lifecycle path of the lifecycle file (not interpreted by P2a)
 * @param endpointProfiles paths of endpoint profiles (not interpreted by P2a)
 * @param legs the report's closed leg vocabulary (BRK-12), never empty
 */
public record ReportDefinition(
    String id,
    String input,
    List<String> scopeTables,
    List<String> deriveTables,
    List<String> validate,
    Optional<String> lifecycle,
    List<String> endpointProfiles,
    List<Leg> legs) {
  public ReportDefinition {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(input, "input");
    scopeTables = List.copyOf(scopeTables);
    deriveTables = List.copyOf(deriveTables);
    validate = List.copyOf(validate);
    Objects.requireNonNull(lifecycle, "lifecycle");
    endpointProfiles = List.copyOf(endpointProfiles);
    legs = List.copyOf(legs);
    if (legs.isEmpty()) {
      throw new IllegalArgumentException("a report needs at least one leg");
    }
  }
}
