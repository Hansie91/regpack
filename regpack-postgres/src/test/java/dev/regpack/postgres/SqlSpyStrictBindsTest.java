package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import org.junit.jupiter.api.Test;

/**
 * Tripwire for {@link SqlSpy#strictBinds()}: the checks the driver makes of the binds of a prepared
 * statement (an index outside the placeholders at the setter, a placeholder without a value at the
 * execution and at the first {@code addBatch}), which the fake server does not make by itself. A
 * test that relied on the strict mode would pass for ever if it silently stopped checking, so the
 * three outcomes and the default are held here.
 */
class SqlSpyStrictBindsTest {
  private static final String TWO = "SELECT ?, ?";
  private static final String NONE = "SELECT 1";

  private static Connection connection(SqlSpy spy) throws SQLException {
    return spy.over(null).getConnection();
  }

  @Test
  void aCompleteStatementIsNotRefused() throws SQLException {
    SqlSpy spy = new SqlSpy().strictBinds();
    try (Connection connection = connection(spy);
        PreparedStatement query = connection.prepareStatement(TWO);
        PreparedStatement update = connection.prepareStatement(TWO);
        PreparedStatement batch = connection.prepareStatement(TWO);
        PreparedStatement none = connection.prepareStatement(NONE)) {
      query.setString(1, "a");
      query.setNull(2, Types.BIGINT);
      query.executeQuery();

      update.setLong(1, 1);
      update.setObject(2, "b");
      update.executeUpdate();

      batch.setString(1, "a");
      batch.setShort(2, (short) 1);
      batch.addBatch();
      // the values stay across addBatch, so the second entry needs no bind of its own
      batch.addBatch();
      batch.executeBatch();

      assertThat(none.execute()).isFalse();
    }
  }

  @Test
  void aPlaceholderWithoutAValueFailsAtTheExecution() throws SQLException {
    SqlSpy spy = new SqlSpy().strictBinds();
    try (Connection connection = connection(spy)) {
      PreparedStatement second = connection.prepareStatement(TWO);
      second.setString(1, "a");
      assertThatThrownBy(second::execute).satisfies(SqlSpyStrictBindsTest::noValueForParameter2);
      assertThatThrownBy(second::executeQuery)
          .satisfies(SqlSpyStrictBindsTest::noValueForParameter2);
      assertThatThrownBy(second::executeUpdate)
          .satisfies(SqlSpyStrictBindsTest::noValueForParameter2);

      PreparedStatement first = connection.prepareStatement(TWO);
      first.setString(2, "b");
      assertThatThrownBy(first::execute)
          .isInstanceOfSatisfying(
              SQLException.class,
              e -> {
                assertThat(e.getSQLState()).isEqualTo("07004");
                assertThat(e.getMessage()).isEqualTo("No value specified for parameter 1.");
              });
    }
    assertThat(spy.statements()).as("a refused execution is not sent").isZero();
  }

  @Test
  void aBatchIsCheckedAtItsFirstEntryOnly() throws SQLException {
    SqlSpy spy = new SqlSpy().strictBinds();
    try (Connection connection = connection(spy)) {
      PreparedStatement batch = connection.prepareStatement(TWO);
      batch.setString(1, "a");
      assertThatThrownBy(batch::addBatch).satisfies(SqlSpyStrictBindsTest::noValueForParameter2);

      batch.setString(2, "b");
      batch.addBatch();
      // only the first entry is checked: the driver keeps the values across addBatch
      batch.clearParameters();
      batch.addBatch();
      assertThat(batch.executeBatch()).hasSize(2);
    }
  }

  @Test
  void clearingTheParametersDropsTheValues() throws SQLException {
    SqlSpy spy = new SqlSpy().strictBinds();
    try (Connection connection = connection(spy)) {
      PreparedStatement statement = connection.prepareStatement(TWO);
      statement.setString(1, "a");
      statement.setString(2, "b");
      statement.execute();
      statement.clearParameters();
      assertThatThrownBy(statement::execute)
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("07004"));
    }
  }

  @Test
  void anIndexOutsideThePlaceholdersFailsAtTheSetter() throws SQLException {
    SqlSpy spy = new SqlSpy().strictBinds();
    try (Connection connection = connection(spy)) {
      PreparedStatement two = connection.prepareStatement(TWO);
      assertThatThrownBy(() -> two.setString(3, "x"))
          .isInstanceOfSatisfying(
              SQLException.class,
              e -> {
                assertThat(e.getSQLState()).isEqualTo("22023");
                assertThat(e.getMessage())
                    .isEqualTo("The column index is out of range: 3, number of columns: 2.");
              });
      assertThatThrownBy(() -> two.setNull(3, Types.BIGINT)).isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> two.setObject(3, "x")).isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> two.setLong(0, 1L)).isInstanceOf(SQLException.class);

      PreparedStatement none = connection.prepareStatement(NONE);
      assertThatThrownBy(() -> none.setString(1, "x"))
          .isInstanceOfSatisfying(
              SQLException.class,
              e ->
                  assertThat(e.getMessage())
                      .isEqualTo("The column index is out of range: 1, number of columns: 0."));
    }
  }

  @Test
  void aPlainStatementIsNotChecked() throws SQLException {
    SqlSpy spy = new SqlSpy().strictBinds();
    try (Connection connection = connection(spy);
        var statement = connection.createStatement()) {
      // a plain statement has no placeholders: nothing is bound and nothing is missing
      statement.execute("SELECT 1");
    }
  }

  @Test
  void theStrictModeIsOffByDefault() throws SQLException {
    SqlSpy spy = new SqlSpy();
    try (Connection connection = connection(spy)) {
      PreparedStatement statement = connection.prepareStatement(TWO);
      statement.setString(3, "beyond the text");
      statement.execute();
      PreparedStatement unbound = connection.prepareStatement(TWO);
      unbound.addBatch();
      unbound.executeUpdate();
    }
  }

  private static void noValueForParameter2(Throwable thrown) {
    assertThat(thrown)
        .isInstanceOfSatisfying(
            SQLException.class,
            e -> {
              assertThat(e.getSQLState()).isEqualTo("07004");
              assertThat(e.getMessage()).isEqualTo("No value specified for parameter 2.");
            });
  }
}
