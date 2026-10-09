package dev.regpack.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class RoleSetTest {
  @Test
  void parsesCsvAndAllExcludesSigner() {
    assertThat(RoleSet.parse("engine,ingest").roles())
        .containsExactlyInAnyOrder(Role.ENGINE, Role.INGEST);
    RoleSet all = RoleSet.parse("all");
    assertThat(all.roles()).hasSize(8).doesNotContain(Role.SIGNER);
    assertThat(RoleSet.defaultRoles()).isEqualTo(all);
  }

  @Test
  void signerMustBeAlone() {
    assertThatThrownBy(() -> RoleSet.parse("engine,signer"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(RoleSet.parse("signer").roles()).isEqualTo(EnumSet.of(Role.SIGNER));
  }

  @Test
  void rejectsUnknownEmptyAndDuplicateRoles() {
    assertThatThrownBy(() -> RoleSet.parse("bogus"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("control-plane");
    assertThatThrownBy(() -> RoleSet.parse("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RoleSet.parse("engine,,ingest"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RoleSet.parse("engine,engine"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void flagRoundTripsForEveryRole() {
    for (Role role : Role.values()) {
      assertThat(Role.parse(role.flag())).isEqualTo(role);
    }
    assertThat(Role.CONTROL_PLANE.flag()).isEqualTo("control-plane");
  }
}
