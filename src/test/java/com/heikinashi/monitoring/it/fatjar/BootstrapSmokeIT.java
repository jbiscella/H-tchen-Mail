package com.heikinashi.monitoring.it.fatjar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Boots the deployable fat jar in a separate JVM with dummy Lambda env vars
 * and asserts the Micronaut context comes up cleanly.
 *
 * <p>Why a separate JVM and not just {@code ApplicationContext.run()}: the
 * test classpath includes transitive deps that aren't in the shaded jar
 * (e.g. SnakeYAML via Cucumber). An in-JVM context boot test passes even
 * when the production fat jar is missing runtime deps. This IT replicates
 * the exact Lambda classpath.
 *
 * <p>Walks the bean graph by resolving both Lambda handlers. Any @Value
 * unresolved, @Factory bean missing, or PropertySourceLoader silently
 * not-loaded surfaces as a BeanInstantiationException on stderr.
 */
class BootstrapSmokeIT {

    private static final long BOOT_TIMEOUT_SECONDS = 30;

    @Test
    void fat_jar_boots_micronaut_context_with_dummy_env() throws Exception {
        File jar = findShadedJar();
        Path stdoutFile = Files.createTempFile("smoke-stdout", ".log");
        Path stderrFile = Files.createTempFile("smoke-stderr", ".log");

        ProcessBuilder pb = new ProcessBuilder(
                javaBinary(), "-cp", jar.getAbsolutePath(), "com.heikinashi.monitoring.smoke.BootstrapSmoke");
        // Dummy Lambda runtime env vars. Real values aren't needed for the
        // context to boot — only the property bindings have to succeed.
        Map<String, String> env = pb.environment();
        env.put("MONITORING_TABLE", "smoke-table");
        env.put("MONITORING_EMAIL_SENDER_EMAIL", "smoke@example.com");
        env.put("MONITORING_EODHD_API_KEY", "smoke-token");
        env.put("MONITORING_BEDROCK_MODEL_ID", "smoke-model");
        env.put("AWS_REGION", "eu-central-1");
        pb.redirectOutput(stdoutFile.toFile());
        pb.redirectError(stderrFile.toFile());

        Process process = pb.start();
        boolean finished = process.waitFor(BOOT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("BootstrapSmoke did not exit within " + BOOT_TIMEOUT_SECONDS + "s\n"
                    + dump("stdout", stdoutFile) + dump("stderr", stderrFile));
        }

        int exit = process.exitValue();
        String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
        String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
        assertThat(exit)
                .as("BootstrapSmoke exit code (stdout=%s stderr=%s)", stdout, stderr)
                .isZero();
        assertThat(stdout)
                .as("stdout should contain BOOT_OK; stderr=%s", stderr)
                .contains("BOOT_OK");
    }

    private static File findShadedJar() {
        File targetDir = new File("target");
        File[] matches = targetDir.listFiles((dir, name) -> name.endsWith("-shaded.jar"));
        if (matches == null || matches.length == 0) {
            throw new AssertionError("No *-shaded.jar in target/ for smoke");
        }
        return matches[0];
    }

    private static String javaBinary() {
        String javaHome = System.getProperty("java.home");
        return javaHome + File.separator + "bin" + File.separator + "java";
    }

    private static String dump(String label, Path file) throws IOException {
        return "--- " + label + " ---\n" + Files.readString(file, StandardCharsets.UTF_8) + "\n";
    }
}
