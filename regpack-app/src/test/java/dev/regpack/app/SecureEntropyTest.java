package dev.regpack.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.security.Entropy;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The production {@link Entropy} adapter. A test of randomness cannot prove quality; these tests
 * show what the adapter is responsible for: the bytes differ between draws, every byte of the array
 * is written, concurrent callers get different bytes, and the construction is the one the notes
 * state. The chance that a correct adapter fails one of them is below 2^-70 (the weakest bound is
 * the one of {@code overwritesWhatTheArrayHeldBefore}).
 */
class SecureEntropyTest {
  private static final HexFormat HEX = HexFormat.of();

  private final Entropy entropy = new SecureEntropy();

  @Test
  void twoDrawsDiffer() {
    byte[] first = new byte[32];
    byte[] second = new byte[32];

    entropy.fill(first);
    entropy.fill(second);

    assertThat(first).isNotEqualTo(second);
    assertThat(first).isNotEqualTo(new byte[32]);
    assertThat(second).isNotEqualTo(new byte[32]);
  }

  @Test
  void twoInstancesDrawDifferentBytes() {
    byte[] first = new byte[32];
    byte[] second = new byte[32];

    new SecureEntropy().fill(first);
    new SecureEntropy().fill(second);

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void fillsTheWholeArray() {
    // A position that the adapter never writes stays zero in every draw; a written position is zero
    // in all 32 draws with probability 2^-256.
    for (int length : new int[] {1, 2, 11, 12, 13, 31, 32, 33, 255, 256, 257, 4096}) {
      boolean[] touched = new boolean[length];
      for (int draw = 0; draw < 32; draw++) {
        byte[] out = new byte[length];
        entropy.fill(out);
        for (int i = 0; i < length; i++) {
          touched[i] |= out[i] != 0;
        }
      }
      for (int i = 0; i < length; i++) {
        assertThat(touched[i]).as("byte %d of an array of %d", i, length).isTrue();
      }
    }
  }

  @Test
  void overwritesWhatTheArrayHeldBefore() {
    byte[] out = new byte[64];
    Arrays.fill(out, (byte) 0x5a);
    byte[] before = out.clone();

    entropy.fill(out);

    int unchanged = 0;
    for (int i = 0; i < out.length; i++) {
      if (out[i] == before[i]) {
        unchanged++;
      }
    }
    // each byte is unchanged with probability 1/256; 16 or more of 64 has probability below 2^-70
    assertThat(unchanged).isLessThan(16);
  }

  @Test
  void anEmptyArrayIsFilledWithoutError() {
    entropy.fill(new byte[0]);
  }

  @Test
  void aNullArrayIsANullPointerException() {
    assertThatThrownBy(() -> entropy.fill(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void isSafeToCallFromEightThreads() throws Exception {
    int threads = 8;
    int drawsEach = 200;
    Set<String> seen = ConcurrentHashMap.newKeySet();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  int zeros = 0;
                  for (int i = 0; i < drawsEach; i++) {
                    byte[] out = new byte[32];
                    entropy.fill(out);
                    seen.add(HEX.formatHex(out));
                    if (Arrays.equals(out, new byte[32])) {
                      zeros++;
                    }
                  }
                  return zeros;
                }));
      }
      start.countDown();
      for (Future<Integer> future : futures) {
        assertThat(future.get(60, TimeUnit.SECONDS)).isZero();
      }
    } finally {
      pool.shutdownNow();
    }

    // 1,600 draws of 32 bytes: a repeat has probability below 2^-230, so every draw is distinct
    assertThat(seen).hasSize(threads * drawsEach);
  }

  @Test
  void holdsOneGeneratorForTheWholeProcessAndNothingElse() {
    List<Field> fields =
        Arrays.stream(SecureEntropy.class.getDeclaredFields())
            .filter(field -> !field.isSynthetic())
            .toList();

    // one field and no other: no instance state, so two instances cannot differ, and no second
    // generator, counter or buffer next to the one generator
    assertThat(fields).extracting(Field::getName).containsExactly("RANDOM");
    Field random = fields.get(0);
    assertThat(random.getType()).isEqualTo(SecureRandom.class);
    int modifiers = random.getModifiers();
    assertThat(Modifier.isStatic(modifiers)).isTrue();
    assertThat(Modifier.isFinal(modifiers)).isTrue();
    assertThat(Modifier.isPrivate(modifiers)).isTrue();
  }

  @Test
  void neverSeedsAndNeverAsksForTheBlockingGenerator() throws IOException {
    // The compiled class is read and not its source, because comments do not reach it. Its constant
    // pool names every method and field it can touch, and the code of fill() has the kinds of
    // instruction it can run: load, constant, stack, field access, invoke and return. So an adapter
    // that draws from another generator, seeds this one, asks for a named or the strong instance,
    // or changes the bytes after a correct draw (a store into the array, an operator, a branch, a
    // loop, a second call) is not this class.
    ClassModel model;
    try (InputStream in =
        SecureEntropy.class.getResourceAsStream(SecureEntropy.class.getSimpleName() + ".class")) {
      assertThat(in).isNotNull();
      model = ClassFile.of().parse(in.readAllBytes());
    }
    List<String> methods = new ArrayList<>();
    List<String> fields = new ArrayList<>();
    for (PoolEntry entry : model.constantPool()) {
      if (entry instanceof MethodRefEntry || entry instanceof InterfaceMethodRefEntry) {
        MemberRefEntry ref = (MemberRefEntry) entry;
        methods.add(
            ref.owner().asInternalName()
                + "."
                + ref.name().stringValue()
                + ref.type().stringValue());
      } else if (entry instanceof FieldRefEntry ref) {
        fields.add(ref.owner().asInternalName() + "." + ref.name().stringValue());
      }
    }

    assertThat(methods)
        .containsExactlyInAnyOrder(
            "java/lang/Object.<init>()V",
            "java/security/SecureRandom.<init>()V",
            "java/security/SecureRandom.nextBytes([B)V",
            "java/util/Objects.requireNonNull(Ljava/lang/Object;Ljava/lang/String;)"
                + "Ljava/lang/Object;");
    assertThat(fields).containsExactly("dev/regpack/app/SecureEntropy.RANDOM");
    MethodModel fill =
        model.methods().stream()
            .filter(
                method ->
                    method.methodName().equalsString("fill")
                        && method.methodType().equalsString("([B)V"))
            .findFirst()
            .orElseThrow();
    Set<Opcode.Kind> kinds = new HashSet<>();
    fill.code()
        .orElseThrow()
        .forEach(
            element -> {
              if (element instanceof Instruction instruction) {
                kinds.add(instruction.opcode().kind());
              }
            });
    assertThat(kinds)
        .contains(Opcode.Kind.INVOKE)
        .isSubsetOf(
            Set.of(
                Opcode.Kind.LOAD,
                Opcode.Kind.CONSTANT,
                Opcode.Kind.STACK,
                Opcode.Kind.FIELD_ACCESS,
                Opcode.Kind.INVOKE,
                Opcode.Kind.RETURN));
  }
}
