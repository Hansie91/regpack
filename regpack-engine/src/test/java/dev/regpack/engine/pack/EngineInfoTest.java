package dev.regpack.engine.pack;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.engine.expr.Library;
import dev.regpack.model.EngineBuild;
import org.junit.jupiter.api.Test;

class EngineInfoTest {
  @Test
  void buildIsParseableAndTracksTheLibraryVersion() {
    EngineBuild build = EngineInfo.BUILD;
    assertThat(EngineBuild.parse(build.toString())).isEqualTo(build);
    assertThat(build.libraryVersion())
        .isEqualTo(Library.VERSION)
        .isEqualTo(EngineInfo.LIBRARY_VERSION);
    assertThat(build.major()).isEqualTo(EngineInfo.SEMANTICS_MAJOR);
    assertThat(build.toString()).isEqualTo("1.0.0+lib1");
    assertThat(EngineInfo.EXECUTOR_ID).isEqualTo(MeteredExecutor.ID);
    // the running build is replay-compatible with itself and satisfies an engine_min equal to it
    assertThat(build.replayCompatibleWith(EngineBuild.parse("1.7.3+lib1"))).isTrue();
    assertThat(build.satisfies(build)).isTrue();
    assertThat(build.replayCompatibleWith(new EngineBuild(2, 0, 0, Library.VERSION))).isFalse();
  }
}
