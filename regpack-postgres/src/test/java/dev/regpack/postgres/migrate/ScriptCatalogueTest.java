package dev.regpack.postgres.migrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ScriptCatalogueTest {
  private static Path migrationDirectory() {
    return Path.of(System.getProperty("regpack.classes.dir")).resolve("db").resolve("migration");
  }

  private static ScriptCatalogue parse(String... names) {
    return ScriptCatalogue.parse(List.of(names), name -> "SELECT 1;\n");
  }

  @Test
  void indexListsEveryScriptFileAndNoOther() throws IOException {
    List<String> files = new ArrayList<>();
    try (Stream<Path> list = Files.list(migrationDirectory())) {
      list.map(p -> p.getFileName().toString()).sorted().forEach(files::add);
    }
    assertThat(files).contains("index.txt");
    List<String> sqlFiles = files.stream().filter(f -> !f.equals("index.txt")).toList();
    assertThat(sqlFiles).as("every file besides index.txt is a V*.sql script").isNotEmpty();
    assertThat(sqlFiles).allMatch(f -> f.matches("V[0-9]{3}__[a-z0-9_]+\\.sql"));

    List<String> indexed =
        ScriptCatalogue.fromClasspath().scripts().stream()
            .map(ScriptCatalogue.Script::name)
            .toList();
    assertThat(indexed).as("the scripts the catalogue reads").isEqualTo(sqlFiles);

    List<String> lines =
        Files.readAllLines(migrationDirectory().resolve("index.txt")).stream()
            .filter(l -> !l.isBlank())
            .toList();
    assertThat(lines).as("index.txt, one script per line, in order").isEqualTo(sqlFiles);
  }

  @Test
  void namesAreVersionedContiguousAndOrdered() {
    assertThat(parse("V001__a.sql").scripts())
        .extracting(ScriptCatalogue.Script::version)
        .containsExactly(1);
    assertThat(parse("V001__a.sql", "V002__b_c.sql").scripts())
        .extracting(ScriptCatalogue.Script::name)
        .containsExactly("V001__a.sql", "V002__b_c.sql");

    for (String[] bad :
        new String[][] {
          {"V002__b.sql"},
          {"V001__a.sql", "V003__c.sql"},
          {"V002__b.sql", "V001__a.sql"},
          {"V001__a.sql", "V001__b.sql"},
          {"V1__a.sql"},
          {"v001__a.sql"},
          {"V001__A.sql"},
          {"V001_a.sql"},
          {"V001__a.txt"},
          {"../V001__a.sql"},
          {"sub/V001__a.sql"},
          {"V001__a.sql", "V001__a.sql"},
        }) {
      assertThatThrownBy(() -> parse(bad))
          .as(String.join(",", bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> ScriptCatalogue.parse(List.of(), name -> ""))
        .as("an empty index")
        .isInstanceOf(IllegalArgumentException.class);

    List<Integer> real =
        ScriptCatalogue.fromClasspath().scripts().stream()
            .map(ScriptCatalogue.Script::version)
            .toList();
    for (int i = 0; i < real.size(); i++) {
      assertThat(real.get(i)).isEqualTo(i + 1);
    }
  }

  @Test
  void checksumIgnoresLineEndings() {
    String lf = "CREATE TABLE a (\n  x int\n);\n";
    String crlf = "CREATE TABLE a (\r\n  x int\r\n);\r\n";
    assertThat(ScriptCatalogue.checksum(crlf)).isEqualTo(ScriptCatalogue.checksum(lf));
    assertThat(ScriptCatalogue.checksum(lf)).matches("[0-9a-f]{64}");
    assertThat(ScriptCatalogue.checksum(lf + " ")).isNotEqualTo(ScriptCatalogue.checksum(lf));
    assertThat(ScriptCatalogue.Script.of(1, "V001__a.sql", crlf).sha256())
        .isEqualTo(ScriptCatalogue.checksum(lf));
    // the catalogue hashes the text it reads, whatever the line ends of the file
    ScriptCatalogue crlfCatalogue = ScriptCatalogue.parse(List.of("V001__a.sql"), name -> crlf);
    assertThat(crlfCatalogue.scripts().get(0).sha256()).isEqualTo(ScriptCatalogue.checksum(lf));
  }

  @Test
  void statementsSplitAtLineEndingSemicolonsOutsideDollarQuotesAndDropCommentLines() {
    String sql =
        String.join(
            "\n",
            "-- a comment; with a semicolon;",
            "CREATE TABLE a (",
            "  x int NOT NULL,",
            "  y text",
            ");",
            "",
            "   -- an indented comment line",
            "CREATE FUNCTION f() RETURNS trigger LANGUAGE plpgsql AS $$",
            "BEGIN",
            "  RETURN NULL;",
            "END;",
            "$$;",
            "INSERT INTO a (x, y) VALUES (1, 'p;q');",
            "");
    List<String> statements = ScriptCatalogue.statements(sql);
    assertThat(statements).hasSize(3);
    assertThat(statements.get(0)).startsWith("CREATE TABLE a (").endsWith(");");
    assertThat(statements.get(1))
        .startsWith("CREATE FUNCTION f()")
        .contains("RETURN NULL;")
        .endsWith("$$;");
    assertThat(statements.get(2)).isEqualTo("INSERT INTO a (x, y) VALUES (1, 'p;q');");
    assertThat(statements).noneMatch(s -> s.contains("--"));
  }

  @Test
  void aScriptWithAnUnterminatedTailOrAnOpenDollarQuoteIsRejected() {
    assertThatThrownBy(() -> ScriptCatalogue.statements("SELECT 1;\nSELECT 2"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ScriptCatalogue.statements("SELECT $$ abc;\n"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ScriptCatalogue.statements("-- nothing but a comment\n\n")).isEmpty();
  }

  @Test
  void theRealFirstScriptSplitsIntoStatementsThatAllStartWithAKnownVerb() {
    ScriptCatalogue.Script first = ScriptCatalogue.fromClasspath().scripts().get(0);
    List<String> statements = first.statements();
    assertThat(statements).isNotEmpty();
    List<String> verbs =
        List.of(
            "CREATE TABLE ",
            "CREATE SEQUENCE ",
            "CREATE INDEX ",
            "CREATE UNIQUE INDEX ",
            "CREATE FUNCTION ",
            "CREATE TRIGGER ",
            "INSERT INTO ");
    for (String statement : statements) {
      assertThat(verbs.stream().anyMatch(statement::startsWith))
          .as("statement starts with a known verb: %s", statement.lines().findFirst().orElse(""))
          .isTrue();
      assertThat(statement).endsWith(";");
    }
  }
}
