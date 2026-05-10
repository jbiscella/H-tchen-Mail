package com.heikinashi.monitoring.infrastructure.email;

import com.heikinashi.monitoring.domain.AiAnalysis;
import com.heikinashi.monitoring.domain.AlertEnrichment;
import com.heikinashi.monitoring.domain.ChartImage;
import com.heikinashi.monitoring.domain.EmailSender;
import com.heikinashi.monitoring.domain.PatternEvent;
import com.heikinashi.monitoring.domain.error.DependencyUnavailableException;
import com.heikinashi.monitoring.domain.error.EmailCompositionException;
import com.heikinashi.monitoring.domain.error.SESConfigurationException;
import jakarta.inject.Singleton;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.mail2.core.EmailException;
import org.apache.commons.mail2.jakarta.HtmlEmail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.RawMessage;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

/**
 * {@link EmailSender} backed by Apache Commons Email (for MIME assembly) +
 * SES v2 raw send (CLAUDE.md §9). One {@link HtmlEmail} per recipient — no
 * BCC — with multipart text + HTML + inline PNG.
 *
 * <p>Per-recipient SES rejection is captured in the returned
 * {@link DeliveryResult} list rather than thrown, so the dispatch
 * orchestration can isolate failures.
 */
@Singleton
public class SesEmailSender implements EmailSender {

    private static final Logger LOG = LoggerFactory.getLogger(SesEmailSender.class);
    private static final String INLINE_NAME = "chart.png";

    private final SesV2Client client;
    private final EmailConfig config;

    public SesEmailSender(SesV2Client client, EmailConfig config) {
        this.client = client;
        this.config = config;
    }

    @Override
    public List<DeliveryResult> sendFull(
            PatternEvent event, ChartImage chart, AiAnalysis analysis, Set<String> recipients) {
        return sendFanout(event, Optional.of(chart), Optional.of(analysis), recipients, AlertEnrichment.FULL);
    }

    @Override
    public List<DeliveryResult> sendDegraded(
            PatternEvent event,
            Optional<ChartImage> chart,
            Optional<AiAnalysis> analysis,
            Set<String> recipients,
            AlertEnrichment enrichment) {
        return sendFanout(event, chart, analysis, recipients, enrichment);
    }

    private List<DeliveryResult> sendFanout(
            PatternEvent event,
            Optional<ChartImage> chart,
            Optional<AiAnalysis> analysis,
            Set<String> recipients,
            AlertEnrichment enrichment) {
        List<DeliveryResult> results = new ArrayList<>(recipients.size());
        for (String recipient : recipients) {
            results.add(deliver(event, chart, analysis, recipient, enrichment));
        }
        return results;
    }

    private DeliveryResult deliver(
            PatternEvent event,
            Optional<ChartImage> chart,
            Optional<AiAnalysis> analysis,
            String recipient,
            AlertEnrichment enrichment) {
        byte[] raw;
        try {
            raw = composeRaw(event, chart, analysis, recipient, enrichment);
        } catch (EmailCompositionException e) {
            LOG.error(
                    "email_compose_failed instrument_id={} recipient_masked={} cause={}",
                    event.instrumentId(),
                    mask(recipient),
                    e.getMessage());
            return new DeliveryResult(recipient, false, Optional.empty(), Optional.of("EMAIL_COMPOSITION_FAILED"));
        }
        try {
            SendEmailResponse resp = client.sendEmail(SendEmailRequest.builder()
                    .fromEmailAddress(config.getSenderEmail())
                    .destination(d -> d.toAddresses(recipient))
                    .content(EmailContent.builder()
                            .raw(RawMessage.builder()
                                    .data(SdkBytes.fromByteArray(raw))
                                    .build())
                            .build())
                    .build());
            return new DeliveryResult(recipient, true, Optional.ofNullable(resp.messageId()), Optional.empty());
        } catch (SesV2Exception e) {
            String code = errorCode(e);
            LOG.warn(
                    "ses_send_failed instrument_id={} recipient_masked={} code={} message={}",
                    event.instrumentId(),
                    mask(recipient),
                    code,
                    e.getMessage());
            return classify(code, recipient, e);
        }
    }

    /**
     * Maps SES error codes to the right disposition:
     *
     * <ul>
     *   <li>recipient-level reject ({@code MessageRejected}, {@code MailFromDomainNotVerified},
     *       address-format issues) → return a failed {@link DeliveryResult} so the dispatch
     *       service can skip this recipient and continue with the others.
     *   <li>transient AWS errors ({@code ThrottlingException}, {@code TooManyRequestsException},
     *       {@code ServiceUnavailableException}, retryable 5xx) → throw
     *       {@link DependencyUnavailableException} so the caller queues a retry.
     *   <li>account-wide config errors ({@code AccountSendingPausedException},
     *       {@code SendingPausedException}, {@code AccessDeniedException}, anything containing
     *       "NotAuthorized") → throw {@link SESConfigurationException} so the Lambda surfaces
     *       to the DLQ; retrying in-run will never help.
     *   <li>anything else → log + recipient-level failure (current fallback).
     * </ul>
     */
    private static DeliveryResult classify(String code, String recipient, SesV2Exception cause) {
        if (code == null) {
            return new DeliveryResult(recipient, false, Optional.empty(), Optional.of("UNKNOWN"));
        }
        if (isTransient(cause, code)) {
            throw new DependencyUnavailableException("ses", cause);
        }
        if (isConfigError(code)) {
            throw new SESConfigurationException(code, cause);
        }
        return new DeliveryResult(recipient, false, Optional.empty(), Optional.of(code));
    }

    private static boolean isTransient(SesV2Exception e, String code) {
        if (e.retryable()) {
            return true;
        }
        int status = e.statusCode();
        if (status >= 500 && status < 600) {
            return true;
        }
        return switch (code) {
            case "ThrottlingException",
                    "TooManyRequestsException",
                    "Throttling",
                    "ServiceUnavailableException",
                    "ServiceUnavailable",
                    "InternalFailure" -> true;
            default -> false;
        };
    }

    private static boolean isConfigError(String code) {
        return switch (code) {
            case "AccountSendingPausedException",
                    "SendingPausedException",
                    "AccessDeniedException",
                    "AccessDenied",
                    "AccountSuspended",
                    "MailFromDomainNotVerifiedException",
                    "NotAuthorized",
                    "UnauthorizedOperation" -> true;
            default -> false;
        };
    }

    private byte[] composeRaw(
            PatternEvent event,
            Optional<ChartImage> chart,
            Optional<AiAnalysis> analysis,
            String recipient,
            AlertEnrichment enrichment) {
        try {
            HtmlEmail email = new HtmlEmail();
            // Commons Email requires a hostname or Session even though we never
            // call send() — we build the MimeMessage and ship raw bytes via SES.
            email.setHostName("localhost");
            email.setCharset(config.getCharset());
            email.setFrom(config.getSenderEmail());
            email.addTo(recipient);
            if (config.getReplyTo() != null && !config.getReplyTo().isBlank()) {
                email.addReplyTo(config.getReplyTo());
            }
            email.setSubject(EmailBodies.subject(config.getSubjectPrefix(), event));

            Optional<String> chartCid = Optional.empty();
            if (chart.isPresent()) {
                ByteArrayDataSource ds =
                        new ByteArrayDataSource(chart.get().bytes(), chart.get().contentType());
                chartCid = Optional.of(email.embed(ds, INLINE_NAME));
            }

            email.setTextMsg(EmailBodies.plainText(event, analysis));
            email.setHtmlMsg(EmailBodies.html(event, chartCid, analysis, enrichment));
            email.buildMimeMessage();

            MimeMessage mime = email.getMimeMessage();
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                mime.writeTo(out);
                return out.toByteArray();
            }
        } catch (EmailException | MessagingException | IOException e) {
            throw new EmailCompositionException(e);
        }
    }

    private static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String errorCode(SesV2Exception e) {
        if (e.awsErrorDetails() == null || e.awsErrorDetails().errorCode() == null) {
            return e.getClass().getSimpleName();
        }
        return e.awsErrorDetails().errorCode();
    }
}
