package com.heikinashi.monitoring.infrastructure.email;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.validation.constraints.NotBlank;

@ConfigurationProperties("monitoring.email")
public class EmailConfig {

    @NotBlank
    private String subjectPrefix = "[HA Alert]";

    @NotBlank
    private String charset = "UTF-8";

    @NotBlank
    private String senderEmail;

    private String replyTo = "";

    public String getSubjectPrefix() {
        return subjectPrefix;
    }

    public void setSubjectPrefix(String subjectPrefix) {
        this.subjectPrefix = subjectPrefix;
    }

    public String getCharset() {
        return charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public String getSenderEmail() {
        return senderEmail;
    }

    public void setSenderEmail(String senderEmail) {
        this.senderEmail = senderEmail;
    }

    public String getReplyTo() {
        return replyTo;
    }

    public void setReplyTo(String replyTo) {
        this.replyTo = replyTo;
    }
}
