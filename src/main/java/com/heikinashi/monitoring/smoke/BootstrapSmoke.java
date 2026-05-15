package com.heikinashi.monitoring.smoke;

import com.heikinashi.monitoring.orchestration.MonitoringMainHandler;
import com.heikinashi.monitoring.orchestration.RetryPollerHandler;
import io.micronaut.context.ApplicationContext;

/**
 * Standalone entry point used only by {@code BootstrapSmokeIT} to verify
 * that the deployable fat jar can boot a full Micronaut context end-to-end
 * — same classpath the Lambda runtime sees, NOT the test classpath (which
 * has extra transitive deps like SnakeYAML-via-Cucumber that mask real
 * production gaps).
 *
 * <p>Lives under {@code src/main/java} on purpose: it must be packaged
 * into the shaded jar so {@code java -cp target/*-shaded.jar} can find it.
 * Never reached at Lambda runtime (the handler classes are the actual
 * entry points there).
 *
 * <p>Prints {@code BOOT_OK} to stdout on success, non-zero exit on any
 * exception. Designed to terminate quickly — no work after bean resolution.
 */
public final class BootstrapSmoke {

    private BootstrapSmoke() {}

    public static void main(String[] args) {
        try (ApplicationContext ctx = ApplicationContext.run("smoke")) {
            // Resolve both Lambda handlers — that walks the entire bean graph,
            // including AppFactory (AWS clients), service-config records,
            // adapters, etc. Any unresolved @Value, missing @Factory bean,
            // or PropertySourceLoader miss surfaces here as a BeanInstantiation
            // exception.
            MonitoringMainHandler main = ctx.getBean(MonitoringMainHandler.class);
            RetryPollerHandler retry = ctx.getBean(RetryPollerHandler.class);
            if (main == null || retry == null) {
                System.err.println("BOOT_FAIL: a handler resolved to null");
                System.exit(2);
            }
            System.out.println("BOOT_OK");
        } catch (Throwable t) {
            // Print the full stack to stderr so the IT can quote it on failure.
            System.err.println("BOOT_FAIL: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
