package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.postgres.SchemaMutants.Entry;
import dev.regpack.postgres.mutants.SchemaMutantLedgerContract;
import dev.regpack.postgres.mutants.SchemaMutation;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Checks that the schema mutants of T20 are what the plan's table says, without a server: a typo in
 * a constraint name or in a test name would make {@link PgSchemaTeethIT} fail (or, worse, pass
 * vacuously) only at CP-3. The real-store behaviour of the mutants is proven by that class.
 */
class SchemaMutantsTest {
  private static final Set<String> PLANNED_OBJECTS =
      Set.of(
          "delivery_in_flight",
          "trn_assignment_lei_trn_key",
          "report_subject_key",
          "decision_idem_key",
          "decision_head_pk",
          "ingest_pk",
          "object_manifest_pk");

  @Test
  void theSevenMutationsOfThePlanAreRegisteredOnceEach() {
    List<String> objects = SchemaMutants.all().stream().map(e -> e.mutation().name()).toList();

    assertThat(objects).containsExactlyInAnyOrderElementsOf(PLANNED_OBJECTS);
    assertThat(objects).doesNotHaveDuplicates();
    assertThat(SchemaMutants.all().stream().map(Entry::type).distinct().count()).isEqualTo(7);
    for (Entry entry : SchemaMutants.all()) {
      assertThat(entry.caughtBy()).as(entry.type().getSimpleName()).isNotEmpty();
    }
  }

  @Test
  void everyNamedTestIsATestMethodOfTheContractBinding() {
    for (Entry entry : SchemaMutants.all()) {
      for (String name : entry.caughtBy()) {
        Method method = null;
        try {
          method = entry.type().getMethod(name);
        } catch (NoSuchMethodException e) {
          // reported by the assertion below
        }
        assertThat(method)
            .as(entry.type().getSimpleName() + " names " + name)
            .isNotNull()
            .matches(m -> m.isAnnotationPresent(Test.class), "is annotated @Test");
      }
    }
  }

  @Test
  void everyMutationNamesAnObjectThatAMigrationCreates() throws IOException {
    String sql = allScripts();
    for (Entry entry : SchemaMutants.all()) {
      SchemaMutation mutation = entry.mutation();
      String table = Pattern.quote(mutation.table());
      String name = Pattern.quote(mutation.name());
      Pattern created =
          switch (mutation.kind()) {
            case CONSTRAINT ->
                Pattern.compile(
                    "CREATE TABLE\\s+"
                        + table
                        + "\\s*\\((?:(?!\\n\\)).)*?CONSTRAINT\\s+"
                        + name
                        + "\\s",
                    Pattern.DOTALL);
            case INDEX ->
                Pattern.compile(
                    "CREATE (?:UNIQUE )?INDEX\\s+" + name + "\\s+ON\\s+" + table + "\\s");
          };
      assertThat(created.matcher(sql).find())
          .as(mutation.kind() + " " + mutation.name() + " on " + mutation.table())
          .isTrue();
    }
  }

  @Test
  void theStatementIsOneQualifiedDropOfTheNamedObject() {
    assertThat(
            SchemaMutation.dropConstraint("trn_assignment", "trn_assignment_lei_trn_key")
                .ddl("t1_1"))
        .isEqualTo("ALTER TABLE t1_1.trn_assignment DROP CONSTRAINT trn_assignment_lei_trn_key");
    assertThat(SchemaMutation.dropIndex("delivery", "delivery_in_flight").ddl("t1_1"))
        .isEqualTo("DROP INDEX t1_1.delivery_in_flight");
  }

  @Test
  void aMutationRefusesAnythingThatIsNotAnIdentifier() {
    assertThatThrownBy(() -> SchemaMutation.dropIndex("delivery", "x; DROP SCHEMA public"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SchemaMutation.dropConstraint("delivery table", "delivery_key"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> SchemaMutation.dropIndex("delivery", "delivery_in_flight").ddl("Public"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void everyRegisteredMutantDeclaresTheMutationOfItsEntry() throws ReflectiveOperationException {
    for (Entry entry : SchemaMutants.all()) {
      SchemaMutantLedgerContract instance = entry.type().getDeclaredConstructor().newInstance();

      assertThat(instance.mutation()).as(entry.type().getSimpleName()).isEqualTo(entry.mutation());
    }
  }

  @Test
  void everyMutantClassOfThePackageIsRegistered() throws IOException, ClassNotFoundException {
    Path directory =
        Path.of(System.getProperty("regpack.test.classes.dir"))
            .resolve("dev/regpack/postgres/mutants");
    List<String> found = new ArrayList<>();
    try (Stream<Path> files = Files.list(directory)) {
      for (Path file : files.toList()) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".class") || name.contains("$")) {
          continue;
        }
        Class<?> type =
            Class.forName("dev.regpack.postgres.mutants." + name.substring(0, name.length() - 6));
        if (SchemaMutantLedgerContract.class.isAssignableFrom(type)
            && !Modifier.isAbstract(type.getModifiers())) {
          found.add(type.getSimpleName());
        }
      }
    }
    List<String> registered =
        SchemaMutants.all().stream()
            .map(e -> e.type().getSimpleName())
            .collect(Collectors.toList());

    assertThat(found)
        .as("a mutant class that is not registered is never run by PgSchemaTeethIT")
        .containsExactlyInAnyOrderElementsOf(new TreeSet<>(registered));
  }

  /** The text of every script named in {@code db/migration/index.txt}, in order, LF only. */
  private static String allScripts() throws IOException {
    StringBuilder all = new StringBuilder();
    for (String line : resource("index.txt").split("\n")) {
      String name = line.strip();
      if (!name.isEmpty()) {
        all.append(resource(name)).append('\n');
      }
    }
    // the scan must see both scripts, or "every mutation names an object" would be weaker than it
    // reads
    assertThat(all.toString())
        .contains("CREATE TABLE ingest (")
        .contains("CREATE TABLE object_manifest (");
    return all.toString();
  }

  private static String resource(String name) throws IOException {
    try (InputStream in = SchemaMutantsTest.class.getResourceAsStream("/db/migration/" + name)) {
      assertThat(in).as(name + " on the classpath").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
