package com.heikinashi.monitoring.it.fatjar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * Post-package validation of the deployable fat jar. Catches the class of
 * bugs that regularly broke prod between PR #31 (shade wiring) and PR #35
 * (SnakeYAML dep):
 *
 * <ul>
 *   <li>jar magro (~330 KB instead of ~40 MB) → shade plugin not bound to package</li>
 *   <li>application.yml under BOOT-INF/ or missing → resources stripped by shading</li>
 *   <li>PropertySourceLoader META-INF/services missing YamlPropertySourceLoader → SnakeYAML
 *       absent at runtime, leading to silent yml parse failure</li>
 * </ul>
 *
 * <p>Pure java.util.zip introspection — no new deps, runs in failsafe so
 * {@code target/*-shaded.jar} is guaranteed to exist.
 */
class FatJarValidationIT {

    private static final long MIN_FAT_JAR_BYTES = 10L * 1024 * 1024;
    private static final String EXPECTED_APPLICATION_YML = "application.yml";
    private static final String PROPERTY_SOURCE_LOADER_SPI =
            "META-INF/services/io.micronaut.context.env.PropertySourceLoader";
    private static final String YAML_LOADER_FQN = "io.micronaut.context.env.yaml.YamlPropertySourceLoader";

    private static File fatJar() {
        File targetDir = new File("target");
        File[] matches = targetDir.listFiles((dir, name) -> name.endsWith("-shaded.jar"));
        if (matches == null || matches.length == 0) {
            throw new AssertionError("No *-shaded.jar in target/. shade plugin not bound to package?");
        }
        if (matches.length > 1) {
            throw new AssertionError("Multiple *-shaded.jar in target/: " + java.util.Arrays.toString(matches));
        }
        return matches[0];
    }

    @Test
    void fat_jar_exists_and_is_a_fat_jar() {
        File jar = fatJar();
        assertThat(jar).exists().isFile();
        assertThat(jar.length())
                .as("fat jar size — anything under 10 MB is almost certainly the thin jar with no deps")
                .isGreaterThanOrEqualTo(MIN_FAT_JAR_BYTES);
    }

    @Test
    void application_yml_is_at_classpath_root_not_under_BOOT_INF() throws IOException {
        try (ZipFile zip = new ZipFile(fatJar())) {
            ZipEntry root = zip.getEntry(EXPECTED_APPLICATION_YML);
            assertThat(root)
                    .as("application.yml at classpath root — Micronaut won't find it elsewhere")
                    .isNotNull();
            ZipEntry bootInf = zip.getEntry("BOOT-INF/classes/" + EXPECTED_APPLICATION_YML);
            assertThat(bootInf)
                    .as("application.yml under BOOT-INF/ is Spring Boot's layout, not Micronaut's")
                    .isNull();
        }
    }

    @Test
    void property_source_loader_spi_includes_yaml_implementation() throws IOException {
        try (ZipFile zip = new ZipFile(fatJar())) {
            ZipEntry spi = zip.getEntry(PROPERTY_SOURCE_LOADER_SPI);
            assertThat(spi)
                    .as(
                            "missing %s → Micronaut can't discover any PropertySourceLoader → yml ignored",
                            PROPERTY_SOURCE_LOADER_SPI)
                    .isNotNull();
            String contents = readEntry(zip, spi);
            assertThat(contents)
                    .as("YamlPropertySourceLoader not registered → application.yml will be silently skipped")
                    .contains(YAML_LOADER_FQN);
        }
    }

    private static String readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry);
                ByteArrayOutputStream out = new ByteArrayOutputStream((int) entry.getSize())) {
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
