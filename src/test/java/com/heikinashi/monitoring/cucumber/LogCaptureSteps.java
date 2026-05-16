package com.heikinashi.monitoring.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Then;
import org.slf4j.LoggerFactory;

/**
 * Captures Logback output for the duration of a scenario so steps can assert
 * on structured log lines (e.g. {@code main_events_suppressed}). A
 * {@link ListAppender} is attached to the root logger before each scenario and
 * detached after, keeping captures scoped per scenario.
 */
public class LogCaptureSteps {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @Before
    public void attach() {
        appender.start();
        root.addAppender(appender);
    }

    @After
    public void detach() {
        root.detachAppender(appender);
        appender.stop();
    }

    @Then("the logs contain a {string} line with {string}")
    public void the_logs_contain_a_line_with(String marker, String fragment) {
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains(marker) && m.contains(fragment));
    }
}
