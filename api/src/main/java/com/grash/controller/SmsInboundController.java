package com.grash.controller;

import com.grash.service.SmsInboundService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Twilio inbound-SMS webhook. Unauthenticated by nature (Twilio POSTs
 * form-encoded From/Body), so it's gated by a random shared secret in the
 * webhook URL's {@code key} query param — we authenticate with a Twilio API
 * Key, not the account Auth Token, so Twilio's HMAC signature scheme (which
 * needs the Auth Token) isn't available to us. A wrong key gets an empty
 * TwiML response (no reply SMS, no information leak).
 */
@RestController
@RequestMapping("/sms")
@RequiredArgsConstructor
@Slf4j
@Hidden
public class SmsInboundController {

    private final SmsInboundService smsInboundService;

    @Value("${twilio.inbound-webhook-key:}")
    private String inboundWebhookKey;

    @PostMapping(value = "/inbound", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> inbound(@RequestParam(value = "key", required = false) String key,
                                          @RequestParam(value = "From", required = false) String from,
                                          @RequestParam(value = "Body", required = false) String body) {
        if (inboundWebhookKey.isEmpty() || !inboundWebhookKey.equals(key)) {
            log.warn("inbound SMS rejected: bad or missing webhook key");
            return ResponseEntity.ok(twiml(null));
        }
        String reply = smsInboundService.handle(from, body);
        return ResponseEntity.ok(twiml(reply));
    }

    private static String twiml(String message) {
        if (message == null || message.isBlank()) {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response/>";
        }
        String escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Message>" + escaped + "</Message></Response>";
    }
}
