package dev.regpack.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.regpack.model.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
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
          "lib/regpack-pack.jar",
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

  /**
   * Unix permission bits per entry name, read by hand from the zip central directory (java.util.zip
   * exposes no external attributes). An entry written by a non-Unix host has no mode and is left
   * out.
   */
  static Map<String, Integer> unixModes(Path zip) throws IOException {
    ByteBuffer zipBytes = ByteBuffer.wrap(Files.readAllBytes(zip)).order(ByteOrder.LITTLE_ENDIAN);
    int eocd = -1;
    for (int i = zipBytes.limit() - 22; i >= 0; i--) {
      if (zipBytes.getInt(i) == 0x06054b50) {
        eocd = i;
        break;
      }
    }
    assertThat(eocd).as("end of central directory record").isNotNegative();
    int entryCount = Short.toUnsignedInt(zipBytes.getShort(eocd + 10));
    int at = zipBytes.getInt(eocd + 16);
    Map<String, Integer> modes = new HashMap<>();
    for (int n = 0; n < entryCount; n++) {
      assertThat(zipBytes.getInt(at)).as("central directory header").isEqualTo(0x02014b50);
      int hostOs = Byte.toUnsignedInt(zipBytes.get(at + 5));
      int nameLength = Short.toUnsignedInt(zipBytes.getShort(at + 28));
      int extraLength = Short.toUnsignedInt(zipBytes.getShort(at + 30));
      int commentLength = Short.toUnsignedInt(zipBytes.getShort(at + 32));
      int externalAttributes = zipBytes.getInt(at + 38);
      byte[] name = new byte[nameLength];
      zipBytes.get(at + 46, name);
      if (hostOs == 3) {
        modes.put(new String(name, StandardCharsets.UTF_8), externalAttributes >>> 16);
      }
      at += 46 + nameLength + extraLength + commentLength;
    }
    return modes;
  }

  @Test
  void theLauncherIsExecutableAndTheOtherFilesAreNot() throws IOException {
    Map<String, Integer> modes = unixModes(distZip());

    assertThat(modes).containsKey(TOP + "bin/regpack");
    assertThat(modes.get(TOP + "bin/regpack") & 0111).as("bin/regpack mode").isEqualTo(0111);
    assertThat(modes.get(TOP + "share/systemd/regpack.service") & 0111).isZero();
    assertThat(modes.get(TOP + "lib/regpack-app.jar") & 0111).isZero();
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
                  "regpack-pack.jar",
                  "regpack-inmemory.jar",
                  "regpack-engine.jar"));
    }
  }

  @Test
  void theAppJarBundlesTheSamplePackByteForByteAndNoThirdPartyJarIsShipped() throws IOException {
    Map<String, byte[]> entries = new HashMap<>();
    try (JarInputStream in =
        new JarInputStream(
            new java.io.ByteArrayInputStream(read(distZip(), "lib/regpack-app.jar")))) {
      for (java.util.jar.JarEntry entry = in.getNextJarEntry();
          entry != null;
          entry = in.getNextJarEntry()) {
        if (!entry.isDirectory()) {
          entries.put(entry.getName(), in.readAllBytes());
        }
      }
    }
    Path sample =
        Path.of(System.getProperty("regpack.repo.root")).resolve("packs").resolve("sample.s0");
    assertThat(entries).containsKeys("packs/sample.s0/CHECKSUMS", "packs/sample.s0/pack.yaml");
    List<Path> sampleFiles;
    try (Stream<Path> walk = Files.walk(sample)) {
      sampleFiles = walk.filter(Files::isRegularFile).toList();
    }
    assertThat(sampleFiles).isNotEmpty();
    for (Path file : sampleFiles) {
      String name = "packs/sample.s0/" + sample.relativize(file).toString().replace('\\', '/');
      assertThat(entries.get(name)).as(name).isEqualTo(Files.readAllBytes(file));
    }
    assertThat(entries.keySet().stream().filter(n -> n.startsWith("packs/")).count())
        .isEqualTo(sampleFiles.size());

    // the lib directory lists the six regpack jars and nothing else: no third-party jar
    assertThat(entryNames(distZip()).stream().filter(n -> n.startsWith("lib/")))
        .allMatch(n -> n.startsWith("lib/regpack-") && n.endsWith(".jar"));
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
    // Output goes to a file so that waiting on the process, not on its stdout, enforces the
    // timeout.
    Path outputFile = temp.resolve("smoke.out");
    Process process =
        new ProcessBuilder(java, "-jar", home.resolve("lib/regpack-app.jar").toString(), "smoke")
            .directory(home.toFile())
            .redirectErrorStream(true)
            .redirectOutput(outputFile.toFile())
            .start();
    if (!process.waitFor(120, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
      fail(
          "smoke did not finish within 120 s; output so far: "
              + Files.readString(outputFile, StandardCharsets.UTF_8));
    }
    String text = Files.readString(outputFile, StandardCharsets.UTF_8);
    assertThat(process.exitValue()).as(text).isZero();
    assertThat(text).contains("regpack smoke: OK decisions=3");
  }
}
