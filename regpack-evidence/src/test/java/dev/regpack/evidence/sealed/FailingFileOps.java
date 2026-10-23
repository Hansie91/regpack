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
import java.util.function.Supplier;

/**
 * Test helper (T6): passes calls to a delegate, except where a rule says that a call fails. A rule
 * names an operation, a predicate on the path of the call (the new name of a link, the target of a
 * replace) and a number of failures, or no limit. A failure is an {@link IOException}, or an {@link
 * UnsupportedOperationException} where a rule says so; it comes before the effect, or after it
 * ({@link #failNextAfterEffect}), which is the case of a call that did its work and then reported
 * an error. Two more faults: {@link #nameTaken} makes a {@code writeAndForce} find its name taken
 * by a file of another writer (planted here, then refused by the real {@code CREATE_NEW}), and
 * {@link #readsAs} makes a read return other bytes. {@link #linkNameTaken} does for a link what
 * {@code nameTaken} does for a write, {@link #linksBy} replaces the link by another action (a copy,
 * a move: a file system whose "hard link" is not one), {@link #failNextWith} fails with an
 * exception of a kind of the test's choice, and {@link #before} and {@link #after} run a step of
 * the test (a latch to wait on, a file to remove) around the calls of an operation.
 */
final class FailingFileOps implements FileOps {
  private static final int UNLIMITED = Integer.MAX_VALUE;

  /** The bytes of the file that {@link #nameTaken} plants. */
  static final byte[] FOREIGN = "a file of another writer".getBytes(StandardCharsets.UTF_8);

  /** A step that a test runs at a call: a latch to wait on, a file to remove. */
  @FunctionalInterface
  interface Hook {
    void run(Path path) throws IOException;
  }

  private record Rule(
      Op op,
      Predicate<Path> when,
      boolean unsupported,
      boolean afterEffect,
      boolean taken,
      Supplier<IOException> thrown,
      AtomicInteger remaining) {}

  private record HookRule(Op op, Predicate<Path> when, boolean afterEffect, Hook hook) {}

  private final FileOps delegate;
  private final boolean claimDirectorySync;
  private final List<Rule> rules = new CopyOnWriteArrayList<>();
  private final List<HookRule> hooks = new CopyOnWriteArrayList<>();
  private final List<Path> planted = new CopyOnWriteArrayList<>();
  private volatile LinkAction linkAction;
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
    return add(op, p -> true, false, false, false, null, 1);
  }

  /** The next call of {@code op} on a path that satisfies {@code when} fails before it acts. */
  FailingFileOps failNext(Op op, Predicate<Path> when) {
    return add(op, when, false, false, false, null, 1);
  }

  /** Every call of {@code op} on a path that satisfies {@code when} fails before it acts. */
  FailingFileOps failAlways(Op op, Predicate<Path> when) {
    return add(op, when, false, false, false, null, UNLIMITED);
  }

  /** The next call of {@code op} does its work and then fails with an IOException. */
  FailingFileOps failNextAfterEffect(Op op) {
    return add(op, p -> true, false, true, false, null, 1);
  }

  /** The next call of {@code op} throws UnsupportedOperationException before it acts. */
  FailingFileOps failNextAsUnsupported(Op op) {
    return add(op, p -> true, true, false, false, null, 1);
  }

  /**
   * The next {@code times} calls of {@code writeAndForce} find their name taken: a file of another
   * writer is created there first, and the real {@code CREATE_NEW} then refuses with a {@link
   * java.nio.file.FileAlreadyExistsException}.
   */
  FailingFileOps nameTaken(int times) {
    return add(Op.WRITE_AND_FORCE, p -> true, false, false, true, null, times);
  }

  /** Every call of {@code writeAndForce} finds its name taken (see {@link #nameTaken}). */
  FailingFileOps nameAlwaysTaken() {
    return nameTaken(UNLIMITED);
  }

  /**
   * The next {@code times} calls of {@code link} find their new name taken: a file of another
   * writer is created there first, and the real link then refuses with a {@link
   * java.nio.file.FileAlreadyExistsException}.
   */
  FailingFileOps linkNameTaken(int times) {
    return add(Op.LINK, p -> true, false, false, true, null, times);
  }

  /** Every call of {@code link} finds its new name taken (see {@link #linkNameTaken}). */
  FailingFileOps linkNameAlwaysTaken() {
    return linkNameTaken(UNLIMITED);
  }

  /**
   * The next call of {@code op} on a path that satisfies {@code when} fails before it acts with the
   * exception that {@code thrown} makes (for a failure of a kind other than a plain IOException).
   */
  FailingFileOps failNextWith(Op op, Predicate<Path> when, Supplier<IOException> thrown) {
    return add(op, when, false, false, false, thrown, 1);
  }

  /** What a file system does when it is asked for a link, in a test that plays a bad one. */
  @FunctionalInterface
  interface LinkAction {
    void link(Path existing, Path newName) throws IOException;
  }

  /**
   * From now on {@code link} does {@code action} instead of passing the call to the delegate (a
   * copy, a move, a link that replaces: the file systems whose "hard link" is not one).
   */
  FailingFileOps linksBy(LinkAction action) {
    linkAction = action;
    return this;
  }

  /** Runs {@code hook} at every call of {@code op} on a path that satisfies {@code when}, first. */
  FailingFileOps before(Op op, Predicate<Path> when, Hook hook) {
    hooks.add(new HookRule(op, when, false, hook));
    return this;
  }

  /** Runs {@code hook} at every such call once the delegate has done its work. */
  FailingFileOps after(Op op, Predicate<Path> when, Hook hook) {
    hooks.add(new HookRule(op, when, true, hook));
    return this;
  }

  private void runHooks(Op op, Path path, boolean afterEffect) throws IOException {
    for (HookRule rule : hooks) {
      if (rule.op() == op && rule.afterEffect() == afterEffect && rule.when().test(path)) {
        rule.hook().run(path);
      }
    }
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
      Supplier<IOException> thrown,
      int times) {
    rules.add(
        new Rule(op, when, unsupported, afterEffect, taken, thrown, new AtomicInteger(times)));
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
    if (rule.thrown() != null) {
      throw rule.thrown().get();
    }
    throw new IOException("injected " + rule.op());
  }

  @Override
  public void createDirectories(Path dir) throws IOException {
    runHooks(Op.CREATE_DIRECTORIES, dir, false);
    Rule rule = fire(Op.CREATE_DIRECTORIES, dir);
    if (rule != null && !rule.afterEffect()) {
      fail(rule);
    }
    delegate.createDirectories(dir);
    runHooks(Op.CREATE_DIRECTORIES, dir, true);
    if (rule != null) {
      fail(rule);
    }
  }

  @Override
  public void writeAndForce(Path file, byte[] bytes) throws IOException {
    runHooks(Op.WRITE_AND_FORCE, file, false);
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
    runHooks(Op.WRITE_AND_FORCE, file, true);
    if (rule != null) {
      fail(rule);
    }
  }

  @Override
  public void link(Path existing, Path newName) throws IOException {
    runHooks(Op.LINK, newName, false);
    Rule rule = fire(Op.LINK, newName);
    if (rule != null && rule.taken()) {
      Files.write(newName, FOREIGN, StandardOpenOption.CREATE_NEW);
      planted.add(newName);
      delegate.link(existing, newName); // refused: FileAlreadyExistsException
      throw new IllegalStateException("the delegate linked over a taken name");
    }
    if (rule != null && !rule.afterEffect()) {
      fail(rule);
    }
    LinkAction replacement = linkAction;
    if (replacement != null) {
      replacement.link(existing, newName);
    } else {
      delegate.link(existing, newName);
    }
    runHooks(Op.LINK, newName, true);
    if (rule != null) {
      fail(rule);
    }
  }

  @Override
  public void replace(Path source, Path target) throws IOException {
    runHooks(Op.REPLACE, target, false);
    Rule rule = fire(Op.REPLACE, target);
    if (rule != null && !rule.afterEffect()) {
      fail(rule);
    }
    delegate.replace(source, target);
    runHooks(Op.REPLACE, target, true);
    if (rule != null) {
      fail(rule);
    }
  }

  @Override
  public void syncDirectory(Path dir) throws IOException {
    runHooks(Op.SYNC_DIRECTORY, dir, false);
    Rule rule = fire(Op.SYNC_DIRECTORY, dir);
    if (rule != null && !rule.afterEffect()) {
      fail(rule);
    }
    delegate.syncDirectory(dir);
    runHooks(Op.SYNC_DIRECTORY, dir, true);
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
    runHooks(Op.READ, file, false);
    Rule rule = fire(Op.READ, file);
    if (rule != null) {
      fail(rule);
    }
    byte[] bytes = delegate.read(file);
    runHooks(Op.READ, file, true);
    return readOverrideWhen.test(file) ? readOverride.clone() : bytes;
  }

  @Override
  public void delete(Path file) throws IOException {
    runHooks(Op.DELETE, file, false);
    Rule rule = fire(Op.DELETE, file);
    if (rule != null && !rule.afterEffect()) {
      fail(rule);
    }
    delegate.delete(file);
    runHooks(Op.DELETE, file, true);
    if (rule != null) {
      fail(rule);
    }
  }

  @Override
  public List<Path> list(Path dir) throws IOException {
    runHooks(Op.LIST, dir, false);
    Rule rule = fire(Op.LIST, dir);
    if (rule != null) {
      fail(rule);
    }
    List<Path> entries = delegate.list(dir);
    runHooks(Op.LIST, dir, true);
    return entries;
  }
}
