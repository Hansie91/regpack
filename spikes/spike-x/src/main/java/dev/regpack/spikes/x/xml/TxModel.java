package dev.regpack.spikes.x.xml;

import java.util.List;

/**
 * One transaction record as the writer takes it: the leaf elements that occur, in document order.
 *
 * <p>{@code names} are paths relative to the record element, segments separated by {@code /}
 * ({@code block/inner/leaf}); a segment of a repeating group carries its occurrence as {@code #n},
 * so that {@code alias#1/x, alias#1/y, alias#2/x} are two {@code alias} elements. {@code values}
 * are the text of the leaves. {@code attributes} are {@code ""} or one {@code name=value} pair per
 * leaf. The three lists have the same size. All three are immutable copies.
 */
public record TxModel(List<String> names, List<String> values, List<String> attributes) {
  public TxModel {
    if (names.size() != values.size() || names.size() != attributes.size()) {
      throw new IllegalArgumentException(
          "names, values and attributes differ in size: "
              + names.size()
              + ", "
              + values.size()
              + ", "
              + attributes.size());
    }
    names = List.copyOf(names);
    values = List.copyOf(values);
    attributes = List.copyOf(attributes);
  }

  /** The number of leaf elements. */
  public int size() {
    return names.size();
  }
}
