package dev.regpack.engine.pack;

import dev.regpack.engine.exec.MeteredExecutor;
import dev.regpack.engine.expr.Library;
import dev.regpack.model.EngineBuild;

/**
 * Identity of this engine build (plan T14; replay rule and {@code engine_min}: companion contracts
 * section 5.1, provisional until gate G-S3b).
 *
 * <p>{@link #SEMANTICS_MAJOR} is bumped by any change that can alter a decision for the same pinned
 * inputs (interpreter or table semantics, the cost model, an expression construct, a ruling);
 * {@link #LIBRARY_VERSION} follows {@link Library#VERSION}. {@code SemanticsFingerprintTest} fails
 * when a ruling, a vector or a cost fixture changes without one of the two being bumped.
 */
public final class EngineInfo {
  /** Major version of the decision semantics. */
  public static final int SEMANTICS_MAJOR = 1;

  /** Version of the built-in function library. */
  public static final int LIBRARY_VERSION = Library.VERSION;

  /** The build identity stamped on every decision: {@code 1.0.0+lib1}. */
  public static final EngineBuild BUILD = new EngineBuild(SEMANTICS_MAJOR, 0, 0, LIBRARY_VERSION);

  /** Id of the executor that runs the programs of this build. */
  public static final String EXECUTOR_ID = MeteredExecutor.ID;

  private EngineInfo() {}
}
