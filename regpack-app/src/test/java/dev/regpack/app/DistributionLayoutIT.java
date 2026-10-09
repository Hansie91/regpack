package dev.regpack.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.regpack.model.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The assembled distribution has exactly the documented layout and runs from its own directory. */
class DistributionLayoutIT {
  private static final String TOP = "regpack-0.1.0-SNAPSHOT/";
  private static final Set<String> JARS =
      Set.of(
          "lib/regpack-app.jar",
          "lib/regpack-model.jar",
          "lib/regpack-ports.jar",
          "lib/regpack-inmemory.jar",
          "lib/regpack-engine.jar");
  private static final List<String> FORBIDDEN_COMPONENT_PREFIXES =
      List.of("junit", "assertj", "opentest4j", "apiguardian", "byte-buddy");

  private static Path distZip() {
    Path buildDir = Path.of(System.getProperty("regpack.build.dir"));
    try (Stream<Path> files = Files.list(buildDir)) {
      List<Path> zips =
          files.filter(p -> p.getFileName().toString().endsWith("-dist.zip")).toList();
      if (zips.size() != 1) {
        return fail("No dist zip found (or more than one) in " + buildDir + ": " + zips);
      }
      return zips.get(0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Set<String> entryNames(Path zip) throws IOException {
    Set<String> names = new HashSet<>();
    try (ZipFile zf = new ZipFile(zip.toFile())) {
      Enumeration<? extends ZipEntry> entries = zf.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        assertThat(entry.getName()).startsWith(TOP);
        if (!entry.isDirectory()) {
          names.add(entry.getName().substring(TOP.length()));
        }
      }
    }
    return names;
  }

  private static byte[] read(Path zip, String name) throws IOException {
    try (ZipFile zf = new ZipFile(zip.toFile())) {
      ZipEntry entry = zf.getEntry(TOP + name);
      assertThat(entry).as(name).isNotNull();
      try (InputStream in = zf.getInputStream(entry)) {
        return in.readAllBytes();
      }
    }
  }

  @Test
  void distributionContainsExactlyTheExpectedFiles() throws IOException {
    Set<String> names = entryNames(distZip());

    Set<String> libs = new HashSet<>();
    names.stream().filter(n -> n.startsWith("lib/")).forEach(libs::add);
    assertThat(libs).isEqualTo(JARS);
    assertThat(names)
        .contains(
            "bin/regpack",
            "bin/regpack.cmd",
            "share/sbom/regpack-bom.json",
            "share/systemd/regpack.service",
            "share/windows/install-procrun.ps1",
            "share/windows/install-service.ps1",
            "share/windows/smoke.ps1");
    assertThat(read(distZip(), "share/systemd/regpack.service")).isNotEmpty();
    for (String name :
        List.of(
            "share/windows/install-procrun.ps1",
            "share/windows/install-service.ps1",
            "share/windows/smoke.ps1")) {
      assertThat(read(distZip(), name)).as(name).isNotEmpty();
    }
    // Nothing else beyond the documented groups.
    List<String> unexpected = new ArrayList<>();
    for (String name : names) {
      boolean known =
          JARS.contains(name)
              || name.equals("bin/regpack")
              || name.equals("bin/regpack.cmd")
              || name.equals("share/sbom/regpack-bom.json")
              || name.equals("share/systemd/regpack.service")
              || (name.startsWith("share/windows/") && name.endsWith(".ps1"));
      if (!known) {
        unexpected.add(name);
      }
    }
    assertThat(unexpected).isEmpty();
  }

  @Test
  void manifestClassPathNamesSiblingJarsWithoutVersions() throws IOException {
    byte[] jar = read(distZip(), "lib/regpack-app.jar");
    try (JarInputStream in = new JarInputStream(new java.io.ByteArrayInputStream(jar))) {
      Manifest manifest = in.getManifest();
      assertThat(manifest).isNotNull();
      assertThat(manifest.getMainAttributes().getValue("Main-Class"))
          .isEqualTo("dev.regpack.app.Main");
      String classPath = manifest.getMainAttributes().getValue("Class-Path");
      assertThat(classPath).isNotNull();
      assertThat(Set.of(classPath.trim().split("\s+")))
          .isEqualTo(
              Set.of(
                  "regpack-model.jar",
                  "regpack-ports.jar",
                  "regpack-inmemory.jar",
                  "regpack-engine.jar"));
    }
  }

  @Test
  void sbomIsCycloneDxJson() throws IOException {
    String text =
        new String(read(distZip(), "share/sbom/regpack-bom.json"), StandardCharsets.UTF_8);
    Object parsed = Json.parse(text);
    assertThat(parsed).isInstanceOf(Map.class);
    Map<?, ?> bom = (Map<?, ?>) parsed;
    assertThat(bom.get("bomFormat")).isEqualTo("CycloneDX");
    assertThat(bom.get("specVersion")).isNotNull();
    assertThat(String.valueOf(bom.get("serialNumber"))).startsWith("urn:uuid:");
    Map<?, ?> metadata = (Map<?, ?>) bom.get("metadata");
    Map<?, ?> component = (Map<?, ?>) metadata.get("component");
    assertThat(component.get("name")).isEqualTo("regpack-app");
    List<?> components = (List<?>) bom.get("components");
    assertThat(components).isNotEmpty();
    List<String> componentNames = new ArrayList<>();
    for (Object c : components) {
      componentNames.add(String.valueOf(((Map<?, ?>) c).get("name")));
    }
    assertThat(componentNames).contains("regpack-engine", "regpack-model");
    for (String prefix : FORBIDDEN_COMPONENT_PREFIXES) {
      assertThat(componentNames).noneMatch(n -> n.startsWith(prefix));
    }
  }

  @Test
  void extractedDistributionRunsTheSmokeCommand(@TempDir Path temp)
      throws IOException, InterruptedException {
    Path zip = distZip();
    try (ZipFile zf = new ZipFile(zip.toFile())) {
      Enumeration<? extends ZipEntry> entries = zf.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        Path target = temp.resolve(entry.getName()).normalize();
        assertThat(target.startsWith(temp)).as(entry.getName()).isTrue();
        if (entry.isDirectory()) {
          Files.createDirectories(target);
        } else {
          Files.createDirectories(target.getParent());
          try (InputStream in = zf.getInputStream(entry)) {
            Files.write(target, in.readAllBytes());
          }
        }
      }
    }
    Path home = temp.resolve(TOP.substring(0, TOP.length() - 1));
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    Process process =
        new ProcessBuilder(java, "-jar", home.resolve("lib/regpack-app.jar").toString(), "smoke")
            .directory(home.toFile())
            .redirectErrorStream(true)
            .start();
    byte[] output = process.getInputStream().readAllBytes();
    assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
    String text = new String(output, StandardCharsets.UTF_8);
    assertThat(process.exitValue()).as(text).isZero();
    assertThat(text).contains("regpack smoke: OK decisions=3");
  }
}
