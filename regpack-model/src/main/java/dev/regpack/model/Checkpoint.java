package dev.regpack.model;

import java.util.Arrays;

/** An opaque resume position of a stream, compared by content. */
public record Checkpoint(String stream, byte[] state) {
  public Checkpoint {
    if (stream == null || stream.isBlank()) {
      throw new IllegalArgumentException("Checkpoint stream must not be blank");
    }
    state = state.clone();
  }

  @Override
  public byte[] state() {
    return state.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Checkpoint c
        && stream.equals(c.stream)
        && Arrays.equals(state, c.state);
  }

  @Override
  public int hashCode() {
    return 31 * stream.hashCode() + Arrays.hashCode(state);
  }

  @Override
  public String toString() {
    return "Checkpoint[stream=" + stream + ", stateLength=" + state.length + "]";
  }
}
