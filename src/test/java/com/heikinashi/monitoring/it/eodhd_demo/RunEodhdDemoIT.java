package com.heikinashi.monitoring.it.eodhd_demo;

import io.cucumber.junit.platform.engine.Constants;
import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * Cucumber suite for the EODHD demo-key network smoke. Class name ends in
 * {@code IT} so Failsafe runs it (Surefire skips it) — keeps the unit-test
 * run fast and offline-friendly. The {@code @network}-tagged scenarios skip
 * (not fail) when EODHD can't be reached.
 *
 * <p>Features live under {@code src/test/resources/features-eodhd/} on
 * purpose: the main {@link com.heikinashi.monitoring.cucumber.RunCucumberTest}
 * scans {@code features/} only, so the in-memory-provider scenarios stay
 * isolated from the real-network ones.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features-eodhd")
@ConfigurationParameter(key = Constants.GLUE_PROPERTY_NAME, value = "com.heikinashi.monitoring.it.eodhd_demo")
public class RunEodhdDemoIT {}
