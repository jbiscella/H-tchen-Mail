package com.heikinashi.monitoring.infrastructure.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.heikinashi.monitoring.domain.AiAnalysis;
import com.heikinashi.monitoring.domain.AiConfidence;
import com.heikinashi.monitoring.domain.AlertEnrichment;
import com.heikinashi.monitoring.domain.BarSnapshot;
import com.heikinashi.monitoring.domain.ChartImage;
import com.heikinashi.monitoring.domain.EmailSender;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.PatternKind;
import com.heikinashi.monitoring.domain.PatternSubtype;
import com.heikinashi.monitoring.domain.Timeframe;
import com.heikinashi.monitoring.domain.error.DependencyUnavailableException;
import com.heikinashi.monitoring.domain.error.SESConfigurationException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

class SesEmailSenderTest {

    private static final PatternEvent EVENT = new PatternEvent(
            "abc-123",
            "AAPL",
            "NASDAQ",
            Timeframe.D1,
            Instant.parse("2026-05-07T00:00:00Z"),
            PatternKind.COLOR_CHANGE,
            PatternSubtype.BULLISH_REVERSAL,
            Map.of(),
            new BarSnapshot(
                    new BigDecimal("100"),
                    new BigDecimal("110"),
                    new BigDecimal("95"),
                    new BigDecimal("105"),
                    Optional.empty(),
                    new BigDecimal("100"),
                    new BigDecimal("110"),
                    new BigDecimal("95"),
                    new BigDecimal("105")),
            Instant.parse("2026-05-07T22:00:00Z"));

    private static final ChartImage CHART =
            new ChartImage(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, "image/png", 900, 500);

    private static final AiAnalysis ANALYSIS =
            new AiAnalysis(Optional.of("a"), Optional.of("b"), AiConfidence.HIGH, List.of("quote_info"));

    @Test
    void sendFull_fans_out_one_request_per_recipient() {
        SesV2Client client = Mockito.mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().messageId("msg-1").build())
                .thenReturn(SendEmailResponse.builder().messageId("msg-2").build());

        SesEmailSender sender = new SesEmailSender(client, defaultConfig());
        Set<String> recipients = orderedSet("alice@example.com", "bob@example.com");

        List<EmailSender.DeliveryResult> results = sender.sendFull(EVENT, CHART, ANALYSIS, recipients);

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(EmailSender.DeliveryResult::delivered);
        verify(client, times(2)).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    void sendFull_request_destination_has_a_single_to_address_per_call() {
        SesV2Client client = Mockito.mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().messageId("m").build());

        SesEmailSender sender = new SesEmailSender(client, defaultConfig());
        sender.sendFull(EVENT, CHART, ANALYSIS, orderedSet("alice@example.com"));

        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(client).sendEmail(captor.capture());
        SendEmailRequest request = captor.getValue();
        assertThat(request.fromEmailAddress()).isEqualTo("alerts@example.com");
        assertThat(request.destination().toAddresses()).containsExactly("alice@example.com");
        assertThat(request.destination().bccAddresses()).isEmpty();
        assertThat(request.destination().ccAddresses()).isEmpty();
        assertThat(request.content().raw().data().asByteArray()).isNotEmpty();

        // The raw content should embed the canonical Subject line.
        String raw = new String(request.content().raw().data().asByteArray(), StandardCharsets.UTF_8);
        assertThat(raw).contains("AAPL.NASDAQ");
        assertThat(raw).contains("Subject:");
        assertThat(raw).contains("multipart");
    }

    @Test
    void per_recipient_SES_failure_does_not_propagate_and_marks_that_recipient_failed() {
        SesV2Client client = Mockito.mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().messageId("ok-1").build())
                .thenThrow(SesV2Exception.builder()
                        .awsErrorDetails(AwsErrorDetails.builder()
                                .errorCode("MessageRejected")
                                .errorMessage("address blocked")
                                .build())
                        .message("address blocked")
                        .build());

        SesEmailSender sender = new SesEmailSender(client, defaultConfig());
        List<EmailSender.DeliveryResult> results =
                sender.sendFull(EVENT, CHART, ANALYSIS, orderedSet("alice@example.com", "bot@example.com"));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).delivered()).isTrue();
        assertThat(results.get(0).sesMessageId()).contains("ok-1");
        assertThat(results.get(1).delivered()).isFalse();
        assertThat(results.get(1).errorCode()).contains("MessageRejected");
    }

    @Test
    void sendDegraded_calls_ses_with_a_well_formed_raw_message() {
        // The degraded HTML placeholders are exercised by EmailBodiesTest. Here we only need to
        // confirm the SES call was made with a non-empty multipart raw body for each recipient.
        SesV2Client client = Mockito.mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().messageId("m").build());

        SesEmailSender sender = new SesEmailSender(client, defaultConfig());
        List<EmailSender.DeliveryResult> results = sender.sendDegraded(
                EVENT,
                Optional.empty(),
                Optional.empty(),
                orderedSet("alice@example.com"),
                AlertEnrichment.DEGRADED_BOTH);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).delivered()).isTrue();

        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(client).sendEmail(captor.capture());
        String raw = new String(captor.getValue().content().raw().data().asByteArray(), StandardCharsets.UTF_8);
        assertThat(raw).contains("multipart");
        assertThat(raw).contains("To: alice@example.com");
    }

    @Test
    void throttling_exception_classified_as_transient_DependencyUnavailable() {
        SesV2Client client = Mockito.mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(SesV2Exception.builder()
                        .awsErrorDetails(AwsErrorDetails.builder()
                                .errorCode("ThrottlingException")
                                .errorMessage("Rate exceeded")
                                .build())
                        .message("Rate exceeded")
                        .statusCode(400)
                        .build());

        SesEmailSender sender = new SesEmailSender(client, defaultConfig());

        assertThatThrownBy(() -> sender.sendFull(EVENT, CHART, ANALYSIS, orderedSet("alice@example.com")))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasMessageContaining("ses");
    }

    @Test
    void server_5xx_classified_as_transient_DependencyUnavailable() {
        SesV2Client client = Mockito.mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(SesV2Exception.builder()
                        .awsErrorDetails(AwsErrorDetails.builder()
                                .errorCode("InternalServerError")
                                .errorMessage("oops")
                                .build())
                        .message("oops")
                        .statusCode(503)
                        .build());

        SesEmailSender sender = new SesEmailSender(client, defaultConfig());

        assertThatThrownBy(() -> sender.sendFull(EVENT, CHART, ANALYSIS, orderedSet("alice@example.com")))
                .isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void access_denied_classified_as_SESConfigurationException() {
        SesV2Client client = Mockito.mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(SesV2Exception.builder()
                        .awsErrorDetails(AwsErrorDetails.builder()
                                .errorCode("AccessDeniedException")
                                .errorMessage("not authorized")
                                .build())
                        .message("not authorized")
                        .statusCode(403)
                        .build());

        SesEmailSender sender = new SesEmailSender(client, defaultConfig());

        assertThatThrownBy(() -> sender.sendFull(EVENT, CHART, ANALYSIS, orderedSet("alice@example.com")))
                .isInstanceOf(SESConfigurationException.class)
                .hasMessageContaining("AccessDeniedException");
    }

    @Test
    void account_sending_paused_classified_as_SESConfigurationException() {
        SesV2Client client = Mockito.mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(SesV2Exception.builder()
                        .awsErrorDetails(AwsErrorDetails.builder()
                                .errorCode("AccountSendingPausedException")
                                .errorMessage("account paused")
                                .build())
                        .message("account paused")
                        .statusCode(400)
                        .build());

        SesEmailSender sender = new SesEmailSender(client, defaultConfig());

        assertThatThrownBy(() -> sender.sendFull(EVENT, CHART, ANALYSIS, orderedSet("alice@example.com")))
                .isInstanceOf(SESConfigurationException.class);
    }

    @Test
    void mailfrom_not_verified_classified_as_SESConfigurationException() {
        SesV2Client client = Mockito.mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(SesV2Exception.builder()
                        .awsErrorDetails(AwsErrorDetails.builder()
                                .errorCode("MailFromDomainNotVerifiedException")
                                .errorMessage("sender not verified")
                                .build())
                        .message("sender not verified")
                        .statusCode(400)
                        .build());

        SesEmailSender sender = new SesEmailSender(client, defaultConfig());

        assertThatThrownBy(() -> sender.sendFull(EVENT, CHART, ANALYSIS, orderedSet("alice@example.com")))
                .isInstanceOf(SESConfigurationException.class);
    }

    private static EmailConfig defaultConfig() {
        EmailConfig config = new EmailConfig();
        config.setSubjectPrefix("[HA Alert]");
        config.setCharset("UTF-8");
        config.setSenderEmail("alerts@example.com");
        config.setReplyTo("");
        return config;
    }

    private static Set<String> orderedSet(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }
}
