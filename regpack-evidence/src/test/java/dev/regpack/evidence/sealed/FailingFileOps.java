package dev.regpack.evidence.sealed;

import dev.regpack.evidence.sealed.RecordingFileOps.Op;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Test helper (T6): passes calls to a delegate, except where a rule says that a call fails. A rule
 * names an operation, a predicate on the path of the call (the new name of a link, the target of a
 * replace) and a number of failures, or no limit. A failure is an {@link IOException}, or an {@link
 * UnsupportedOperationException} where a rule says so; it comes before the effect, or after it
 * ({@link #failNextAfterEffect}), which is the case of a call that did its work and then reported
 * an error. Two more faults: {@link #nameTaken} makes a {@code writeAndForce} find its name taken
 * by a file of another writer (planted here, then refused by the real {@code CREATE_NEW}), and
 * {@link #readsAs} makes a read return other bytes.
 */
final class FailingFileOps implements FileOps {
  private static final int UNLIMITED = Integer.MAX_VALUE;

  /** The bytes of the file that {@link #nameTaken} plants. */
  static final byte[] FOREIGN = "a file of another writer".getBytes(StandardCharsets.UTF_8);

  private record Rule(
      Op op,
      Predicate<Path> when,
      boolean unsupported,
      boolean afterEffect,
      boolean taken,
      AtomicInteger remaining) {}

  private final FileOps delegate;
  private final boolean claimDirectorySync;
  private final List<Rule> rules = new CopyOnWriteArrayList<>();
  private final List<Path> planted = new CopyOnWriteArrayList<>();
  private volatile Predicate<Path> readOverrideWhen = p -> false;
  private volatile byte[] readOverride = new byte[0];

  FailingFileOps(FileOps delegate) {
    this(delegate, false);
  }

  FailingFileOps(FileOps delegate, boolean claimDirectorySync) {
    this.delegate = delegate;
    this.claimDirectorySync = claimDirectorySync;
  }

  /** The next call of {@code op} fails with an IOException before it does anything. */
  FailingFileOps failNext(Op op) {
    return add(op, p -> true, false, false, false, 1);
  }

  /** The next call of {@code op} on a path that satisfies {@code when} fails before it acts. */
  FailingFileOps failNext(Op op, Predicate<Path> when) {
    return add(op, when, false, false, false, 1);
  }

  /** Every call of {@code op} on a path that satisfies {@code when} fails before it acts. */
  FailingFileOps failAlways(Op op, Predicate<Path> when) {
    return add(op, when, false, false, false, UNLIMITED);
  }

  /** The next call of {@code op} does its work and then fails with an IOException. */
  FailingFileOps failNextAfterEffect(Op op) {
    return add(op, p -> true, false, true, false, 1);
  }

  /** The next call of {@code op} throws UnsupportedOperationException before it acts. */
  FailingFileOps failNextAsUnsupported(Op op) {
    return add(op, p -> true, true, false, false, 1);
  }

  /**
   * The next {@code times} calls of {@code writeAndForce} find their name taken: a file of another
   * writer is created there first, and the real {@code CREATE_NEW} then refuses with a {@link
   * java.nio.file.FileAlreadyExistsException}.
   */
  FailingFileOps nameTaken(int times) {
    return add(Op.WRITE_AND_FORCE, p -> true, false, false, true, times);
  }

  /** Every call of {@code writeAndForce} finds its name taken (see {@link #nameTaken}). */
  FailingFileOps nameAlwaysTaken() {
    return nameTaken(UNLIMITED);
  }

  /** The files that {@link #nameTaken} created, in order; each holds {@link #FOREIGN}. */
  List<Path> planted() {
    return List.copyOf(planted);
  }

  /** From now on a read of a path that satisfies {@code when} returns {@code bytes}. */
  FailingFileOps readsAs(Predicate<Path> when, byte[] bytes) {
    readOverrideWhen = when;
    readOverride = bytes.clone();
    return this;
  }

  private FailingFileOps add(
      Op op,
      Predicate<Path> when,
      boolean unsupported,
      boolean afterEffect,
      boolean taken,
      int times) {
    rules.add(new Rule(op, when, unsupported, afterEffect, taken, new AtomicInteger(times)));
    return this;
  }

  /** The rule that fires for this call, if any; it is used up by one failure. */
  private Rule fire(Op op, Path path) {
    for (Rule rule : rules) {
      if (rule.op() != op || !rule.when().test(path)) {
        continue;
      }
      while (true) {
        int left = rule.remaining().get();
        if (left <= 0) {
          break;
        }
        if (left == UNLIMITED || rule.remaining().compareAndSet(left, left - 1)) {
          return rule;
        }
      }
    }
    return null;
  }

  private static void fail(Rule rule) throws IOException {
    if (rule.unsupported()) {
      throw new UnsupportedOperationException("injected " + rule.op());
    }
    throw new IOException("injected " + rule.op());
  }

  @Override
  public void createDirectories(Path dir) throws IOException {
    Rule rule = fire(Op.CREATE_DIRECTORIES, dir);
    if (rule != null && !rule.afterEffect()) {
      fail(rule);
    }
    delegate.createDirectories(dir);
    if (rule != null) {
      fail(rule);
    }
  }

  @Override
  public void writeAndForce(Path file, byte[] bytes) throws IOException {
    Rule rule = fire(Op.WRITE_AND_FORCE, file);
    if (rule != null && rule.taken()) {
      Files.write(file, FOREIGN, StandardOpenOption.CREATE_NEW);
      planted.add(file);
      delegate.writeAndForce(file, bytes); // refused: FileAlreadyExistsException
      throw new IllegalStateException("the delegate wrote over a taken name");
    }
    if (rule != null && !rule.afterEffect()) {
      fail(rule);
    }
    delegate.writeAndForce(file, bytes);
    if (rule != null) {
      fail(rule);
    }
  }

  @Override
  public void link(Path existing, Path newName) throws IOException {
    Rule rule = fire(Op.LINK, newName);
    if (rule != null && !rule.afterEffect()) {
      fail(rule);
    }
    delegate.link(existing, newName);
    if (rule != null) {
      fail(rule);
    }
  }

  @Override
  public void replace(Path source, Path target) throws IOException {
    Rule rule = fire(Op.REPLACE, target);
    if (rule != null && !rule.afterEffect()) {
      fail(rule);
    }
    delegate.replace(source, target);
    if (rule != null) {
      fail(rule);
    }
  }

  @Override
  public void syncDirectory(Path dir) throws IOException {
    Rule rule = fire(Op.SYNC_DIRECTORY, dir);
    if (rule != null && !rule.afterEffect()) {
      fail(rule);
    }
    delegate.syncDirectory(dir);
    if (rule != null) {
      fail(rule);
    }
  }

  @Override
  public boolean supportsDirectorySync() {
    return claimDirectorySync || delegate.supportsDirectorySync();
  }

  @Override
  public byte[] read(Path file) throws IOException {
    Rule rule = fire(Op.READ, file);
    if (rule != null) {
      fail(rule);
    }
    byte[] bytes = delegate.read(file);
    return readOverrideWhen.test(file) ? readOverride.clone() : bytes;
  }

  @Override
  public void delete(Path file) throws IOException {
    Rule rule = fire(Op.DELETE, file);
    if (rule != null && !rule.afterEffect()) {
      fail(rule);
    }
    delegate.delete(file);
    if (rule != null) {
      fail(rule);
    }
  }

  @Override
  public List<Path> list(Path dir) throws IOException {
    Rule rule = fire(Op.LIST, dir);
    if (rule != null) {
      fail(rule);
    }
    return delegate.list(dir);
  }
}
