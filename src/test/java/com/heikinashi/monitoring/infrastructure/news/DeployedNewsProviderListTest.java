package com.heikinashi.monitoring.infrastructure.news;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.context.ApplicationContext;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the provider list the <em>deployed</em> application binds, loaded from the real
 * {@code application.yml} rather than from {@link NewsConfig}'s field initializer.
 *
 * <p>This exists because of a Codex P1 on PR #88. Block 19 added {@code tavily} to the field
 * initializer, which looks like enabling it — but {@code application.yml} explicitly lists the
 * providers, and Micronaut's YAML binding overrides the initializer. The provider would have been
 * silently filtered out of every deployed run, key or no key, and no unit test touching
 * {@code NewsConfig} directly could have noticed: they all construct the object and see the
 * initializer's value.
 *
 * <p>So the assertion has to come from a real {@link ApplicationContext}. Anything cheaper tests
 * the wrong source of truth.
 */
class DeployedNewsProviderListTest {

    @Test
    void the_deployed_configuration_enables_every_implemented_news_provider() {
        try (ApplicationContext context = ApplicationContext.run()) {
            List<String> providers = context.getBean(NewsConfig.class).getProviders();

            assertThat(providers).contains("marketaux", "yahoo-rss", "eodhd", "tavily");
        }
    }
}
