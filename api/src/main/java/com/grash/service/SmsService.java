package com.grash.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Transactional SMS via Twilio, authenticating with an API Key (SK.../secret)
 * rather than the account's primary Auth Token — same scoped-credential
 * pattern as criticalasset-osint's app/services/sms.py, and reuses the same
 * Twilio account/number. Best-effort and silent by design: a missing config
 * or a Twilio-side failure never blocks the work-order notification flow
 * that's calling this — it's just logged.
 */
@Service
@Slf4j
public class SmsService {

    private static final String TWILIO_API_BASE = "https://api.twilio.com/2010-04-01";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${twilio.account-sid:}")
    private String accountSid;
    @Value("${twilio.api-key-sid:}")
    private String apiKeySid;
    @Value("${twilio.api-key-secret:}")
    private String apiKeySecret;
    @Value("${twilio.from-number:}")
    private String fromNumber;

    public boolean isConfigured() {
        return !accountSid.isEmpty() && !apiKeySid.isEmpty()
                && !apiKeySecret.isEmpty() && !fromNumber.isEmpty();
    }

    /**
     * Fire-and-forget: send() never throws. Returns true only on a
     * confirmed Twilio 2xx; every other outcome (not configured, no/invalid
     * phone, network error, Twilio error) is logged and returns false.
     */
    public boolean send(String toPhone, String body) {
        if (!isConfigured()) {
            return false;
        }
        if (toPhone == null || toPhone.isBlank()) {
            return false;
        }
        try {
            String form = Map.of("From", fromNumber, "To", toPhone, "Body", body)
                    .entrySet().stream()
                    .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                    .collect(Collectors.joining("&"));
            String credentials = Base64.getEncoder().encodeToString(
                    (apiKeySid + ":" + apiKeySecret).getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TWILIO_API_BASE + "/Accounts/" + accountSid + "/Messages.json"))
                    .header("Authorization", "Basic " + credentials)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("SMS send failed: HTTP {} - {}", response.statusCode(), response.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("SMS send error", e);
            return false;
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
