package dev.regpack.spikes.r;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PhysicalCoresTest {
  private static String processor(int index, int physical, int core) {
    return "processor\t: "
        + index
        + "\nmodel name\t: Test CPU\nphysical id\t: "
        + physical
        + "\nsiblings\t: 4\ncore id\t\t: "
        + core
        + "\ncpu cores\t: 2\n\n";
  }

  @Test
  void fourLogicalProcessorsOnTwoCoresAreTwoPhysicalCores() {
    String cpuinfo =
        processor(0, 0, 0) + processor(1, 0, 1) + processor(2, 0, 0) + processor(3, 0, 1);
    assertThat(PhysicalCores.parse(cpuinfo, 4)).isEqualTo(2);
  }

  @Test
  void twoSocketsWithTheSameCoreIdsAreFourCores() {
    String cpuinfo =
        processor(0, 0, 0) + processor(1, 0, 1) + processor(2, 1, 0) + processor(3, 1, 1);
    assertThat(PhysicalCores.parse(cpuinfo, 4)).isEqualTo(4);
  }

  @Test
  void aTextWithoutCoresFallsBackToTheLogicalCount() {
    assertThat(PhysicalCores.parse("", 4)).isEqualTo(4);
    assertThat(PhysicalCores.parse("model name : x\n", 8)).isEqualTo(8);
  }

  @Test
  void theCountNeverExceedsTheLogicalProcessors() {
    String cpuinfo = processor(0, 0, 0) + processor(1, 0, 1) + processor(2, 0, 2);
    assertThat(PhysicalCores.parse(cpuinfo, 2)).isEqualTo(2);
  }

  @Test
  void theLiveCountIsAtLeastOne() {
    assertThat(PhysicalCores.detect()).isBetween(1, Runtime.getRuntime().availableProcessors());
  }
}
