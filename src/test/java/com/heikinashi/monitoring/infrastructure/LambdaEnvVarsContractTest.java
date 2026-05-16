package com.heikinashi.monitoring.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract test: every env var the app actually needs at Lambda runtime must
 * appear in {@code terraform/main/lambda.tf}'s {@code environment.variables}
 * block on both functions.
 *
 * <p>This catches the class of bug that hit prod between PR #1 (adding
 * {@code EmailConfig.senderEmail = @NotBlank}) and PR #32 (wiring the env
 * var into lambda.tf), and again between {@code var.bedrock_model_id} being
 * declared (PR #28) and {@code MONITORING_BEDROCK_MODEL_ID} being threaded
 * into env vars (PR #38). In both cases the Java side expected an env var,
 * the Terraform side never set it, and Lambda boot crashed.
 *
 * <p>List is intentionally manual — adding a new env-overridable property
 * is a deliberate decision, and forcing operators to update this list keeps
 * the contract explicit and reviewable.
 */
class LambdaEnvVarsContractTest {

    private static final Path LAMBDA_TF = Path.of("terraform", "main", "lambda.tf");

    private static final List<String> REQUIRED_ENV_VARS = List.of(
            "LOG_LEVEL",
            "MONITORING_TABLE",
            "MONITORING_EMAIL_SENDER_EMAIL",
            "MONITORING_EODHD_API_KEY",
            "MONITORING_MARKETAUX_API_KEY",
            "MONITORING_MARKETAUX_RECENCY_DAYS_1D",
            "MONITORING_MARKETAUX_RECENCY_DAYS_1W",
            "MONITORING_BEDROCK_MODEL_ID");

    @Test
    void every_required_env_var_is_set_in_lambda_tf() throws IOException {
        String lambdaTf = Files.readString(LAMBDA_TF);
        for (String envVar : REQUIRED_ENV_VARS) {
            assertThat(lambdaTf)
                    .as(
                            "Env var %s not found in %s. Add to BOTH the monitoring-main and retry-poller "
                                    + "environment.variables blocks, or remove from REQUIRED_ENV_VARS if no longer needed.",
                            envVar, LAMBDA_TF)
                    .contains(envVar);
        }
    }

    @Test
    void every_required_env_var_appears_at_least_twice_one_per_lambda() throws IOException {
        String lambdaTf = Files.readString(LAMBDA_TF);
        for (String envVar : REQUIRED_ENV_VARS) {
            int count = countOccurrences(lambdaTf, envVar);
            assertThat(count)
                    .as(
                            "Env var %s should be in environment.variables on BOTH Lambdas (monitoring-main + retry-poller). Found %d occurrences.",
                            envVar, count)
                    .isGreaterThanOrEqualTo(2);
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
