package dev.regpack.spikes.l.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The offline part of the fixture: how the PostgreSQL URL is read (review focus RF11). */
class PgFixtureFromPropertyTest {
  private static final String PROPERTY = "spike.pg.url";
  private static final String PRIMARY = "jdbc:postgresql://127.0.0.1:5432/postgres?user=postgres";

  private String saved;

  @BeforeEach
  void saveProperty() {
    saved = System.getProperty(PROPERTY);
    System.clearProperty(PROPERTY);
  }

  @AfterEach
  void restoreProperty() {
    if (saved == null) {
      System.clearProperty(PROPERTY);
    } else {
      System.setProperty(PROPERTY, saved);
    }
  }

  @Test
  void absentPropertyIsAbsent() {
    assertThat(PgFixture.fromSystemProperty()).isEmpty();
  }

  @Test
  void blankPropertyIsAbsent() {
    System.setProperty(PROPERTY, "");
    assertThat(PgFixture.fromSystemProperty()).isEmpty();
    System.setProperty(PROPERTY, "  ");
    assertThat(PgFixture.fromSystemProperty()).isEmpty();
  }

  @Test
  void derivedUrlsKeepTheUserAndSwapThePort() {
    System.setProperty(PROPERTY, PRIMARY);
    Optional<PgFixture> fixture = PgFixture.fromSystemProperty();
    assertThat(fixture).isPresent();
    try (PgFixture pg = fixture.orElseThrow()) {
      assertThat(pg.primaryUrl()).isEqualTo(PRIMARY);
      assertThat(pg.standbyUrl())
          .isEqualTo("jdbc:postgresql://127.0.0.1:5433/postgres?user=postgres");
      assertThat(pg.replicaUrl())
          .isEqualTo("jdbc:postgresql://127.0.0.1:5434/postgres?user=postgres");
    }
  }

  @Test
  void aSurroundingBlankIsTrimmed() {
    System.setProperty(PROPERTY, "  " + PRIMARY + "\n");
    try (PgFixture pg = PgFixture.fromSystemProperty().orElseThrow()) {
      assertThat(pg.primaryUrl()).isEqualTo(PRIMARY);
    }
  }

  @Test
  void aUrlWithoutPortGetsThePortOfTheRole() {
    try (PgFixture pg = PgFixture.of("jdbc:postgresql://localhost/postgres?user=postgres")) {
      assertThat(pg.standbyUrl())
          .isEqualTo("jdbc:postgresql://localhost:5433/postgres?user=postgres");
      assertThat(pg.replicaUrl())
          .isEqualTo("jdbc:postgresql://localhost:5434/postgres?user=postgres");
    }
  }

  @Test
  void anIpv6HostKeepsItsBrackets() {
    try (PgFixture pg = PgFixture.of("jdbc:postgresql://[::1]:5432/postgres?user=postgres")) {
      assertThat(pg.standbyUrl()).isEqualTo("jdbc:postgresql://[::1]:5433/postgres?user=postgres");
    }
  }

  @Test
  void aUrlThatIsNotPostgresqlIsRejected() {
    assertThatThrownBy(() -> PgFixture.of("jdbc:mysql://127.0.0.1:3306/x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("jdbc:postgresql://");
  }
}
