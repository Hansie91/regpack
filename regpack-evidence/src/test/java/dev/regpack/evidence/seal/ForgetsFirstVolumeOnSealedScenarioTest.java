package dev.regpack.evidence.seal;

import dev.regpack.evidence.sealed.ForgetfulVolume;
import dev.regpack.evidence.sealed.SealedEvidenceStore;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately wrong evidence store (D-E10, the file-operations form): over P-SEALED on real
 * directories, the first volume acknowledges every object file it is given and keeps none. The
 * mirror of {@code ForgetsSecondVolume}: the two scenarios of the loss of a volume are the teeth of
 * one each. {@code SealingTeethTest} expects the named scenarios to fail.
 */
@Tag("mutant")
class ForgetsFirstVolumeOnSealedScenarioTest extends SealScenariosOnSealedTest {
  @Override
  SealedEvidenceStore openStore(SealedEvidenceStore.Config volumes) {
    return ForgetfulVolume.open(volumes, true);
  }
}
