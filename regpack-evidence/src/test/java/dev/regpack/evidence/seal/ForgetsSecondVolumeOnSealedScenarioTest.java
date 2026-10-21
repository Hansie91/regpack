package dev.regpack.evidence.seal;

import dev.regpack.evidence.sealed.ForgetfulVolume;
import dev.regpack.evidence.sealed.SealedEvidenceStore;
import org.junit.jupiter.api.Tag;

/**
 * Deliberately wrong evidence store (D-E10, the file-operations form): over P-SEALED on real
 * directories, the second volume acknowledges every object file it is given and keeps none, so the
 * store acknowledges a put that one volume alone holds. {@code SealingTeethTest} expects the named
 * scenarios to fail.
 */
@Tag("mutant")
class ForgetsSecondVolumeOnSealedScenarioTest extends SealScenariosOnSealedTest {
  @Override
  SealedEvidenceStore openStore(SealedEvidenceStore.Config volumes) {
    return ForgetfulVolume.open(volumes, false);
  }
}
