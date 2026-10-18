package dev.regpack.bench.proto;

/** A source of monotonic nanoseconds; tests script it. */
@FunctionalInterface
public interface NanoClock {
  long nanoTime();

  /** The JVM's own monotonic clock. */
  static NanoClock system() {
    return System::nanoTime;
  }
}
