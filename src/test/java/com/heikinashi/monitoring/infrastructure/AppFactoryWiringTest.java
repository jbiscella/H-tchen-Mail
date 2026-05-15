package com.heikinashi.monitoring.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.heikinashi.monitoring.application.AlertDispatchService;
import com.heikinashi.monitoring.application.MonitoringRunService;
import com.heikinashi.monitoring.application.RetryPollerService;
import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/**
 * Smoke test that boots the Micronaut ApplicationContext and resolves every
 * bean the Lambda handlers ultimately need. Catches the class of wiring bug
 * that ChatGPT's review surfaced: missing factory methods for AWS SDK
 * clients and unannotated primitives on the application services.
 *
 * <p>The AWS SDK clients are built lazily — instantiating them here never
 * talks to AWS, it just exercises the bean graph.
 */
class AppFactoryWiringTest {

    private static final Map<String, Object> OVERRIDES =
            Map.of("monitoring.email.sender-email", "alerts@test.local", "monitoring.eodhd.api-key", "test-token");

    @Test
    void context_boots_and_provides_aws_clients() {
        try (ApplicationContext ctx = ApplicationContext.run(OVERRIDES, "test")) {
            assertThat(ctx.getBean(DynamoDbClient.class)).isNotNull();
            assertThat(ctx.getBean(BedrockRuntimeClient.class)).isNotNull();
            assertThat(ctx.getBean(SesV2Client.class)).isNotNull();
        }
    }

    @Test
    void context_resolves_application_services_with_config_injection() {
        try (ApplicationContext ctx = ApplicationContext.run(OVERRIDES, "test")) {
            assertThat(ctx.getBean(AlertDispatchService.class)).isNotNull();
            assertThat(ctx.getBean(RetryPollerService.class)).isNotNull();
            assertThat(ctx.getBean(MonitoringRunService.class)).isNotNull();
        }
    }
}
