package dev.regpack.bench.proto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * The child JVM of {@link ForkRunnerTest}. Arguments: {@code mode [file]}. Modes: {@code ok},
 * {@code mark} (appends a line to the file first), {@code echo} (writes three system properties to
 * the file first), {@code noline} (prints no result line), {@code fail} (prints the line, then
 * exits with 3), {@code twolines} (prints two result lines), {@code emptywindows} (prints a result
 * line that is not discarded and has no window rate).
 */
public final class TestWorker {
  private TestWorker() {}

  private static String property(String name) {
    String value = System.getProperty(name);
    return name + "=" + (value == null ? "ABSENT" : value);
  }

  private static ForkResult result() {
    return new ForkResult(
        0, false, false, "", List.of(1.5, 2.5, 3.5), List.of(), List.of(), List.of(), 0.0, 42);
  }

  public static void main(String[] args) throws IOException {
    String mode = args[0];
    System.out.println("worker starting in mode " + mode);
    switch (mode) {
      case "mark" ->
          Files.writeString(
              Path.of(args[1]),
              "started\n",
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND);
      case "echo" ->
          Files.write(
              Path.of(args[1]),
              List.of(property("spike.pg.url"), property("x.other"), property("child.marker")),
              StandardCharsets.UTF_8);
      default -> {
        // nothing to prepare
      }
    }
    switch (mode) {
      case "noline" -> System.out.println("this is not a result line");
      case "twolines" -> {
        System.out.println(result().toLine());
        System.out.println(result().withIndex(5).toLine());
      }
      case "emptywindows" ->
          System.out.println(
              ForkResult.LINE_PREFIX
                  + "{\"canaryCpu\":[],\"canaryDisk\":[],\"canaryMemory\":[],\"checksum\":1,"
                  + "\"discardReason\":\"\",\"discarded\":false,\"index\":0,"
                  + "\"stealFraction\":0.000000000,\"unstable\":false,\"windowRates\":[]}");
      case "fail" -> {
        System.out.println(result().toLine());
        System.exit(3);
      }
      default -> System.out.println(result().toLine());
    }
  }
}
