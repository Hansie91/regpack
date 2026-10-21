package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The copy of the test data source that the job {@code ledger-durability} uses for its fixtures
 * ({@link PgTestDatabase#dataSourceWithOptions}), without a server. The URL that a data source
 * generates leaves out the user and the password, so a copy made from it alone would connect as
 * another user.
 */
class PgTestDatabaseOptionsTest {
  @Test
  void aCopyKeepsTheServerTheUserThePasswordAndTheOtherParametersAndAddsTheOptions() {
    PGSimpleDataSource base = new PGSimpleDataSource();
    base.setUrl(
        "jdbc:postgresql://127.0.0.1:5432/postgres"
            + "?user=postgres&password=not-a-real-one&sslmode=disable");

    DataSource copy = PgTestDatabase.withOptions(base, "-c synchronous_commit=local");

    assertThat(copy).isInstanceOf(PGSimpleDataSource.class).isNotSameAs(base);
    PGSimpleDataSource result = (PGSimpleDataSource) copy;
    assertThat(result.getUser()).isEqualTo("postgres");
    assertThat(result.getPassword()).isEqualTo("not-a-real-one");
    assertThat(result.getOptions()).isEqualTo("-c synchronous_commit=local");
    assertThat(result.getDatabaseName()).isEqualTo("postgres");
    assertThat(result.getUrl()).startsWith("jdbc:postgresql://127.0.0.1:5432/postgres?");
    assertThat(result.getUrl()).contains("sslmode=disable");
    assertThat(base.getOptions()).as("the original is not changed").isNull();
  }

  @Test
  void aSourceThatIsNotAPostgresSimpleDataSourceIsRefused() {
    DataSource other = new SqlSpy().over(null);
    assertThatThrownBy(() -> PgTestDatabase.withOptions(other, "-c synchronous_commit=local"))
        .isInstanceOf(IllegalStateException.class);
  }
}
