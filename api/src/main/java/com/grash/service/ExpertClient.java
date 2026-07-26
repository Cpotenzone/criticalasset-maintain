package com.grash.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Java client for CriticalAsset's own served fine-tuned models (Gemma-3-27B
 * base + LoRA adapters, OpenAI-compatible /v1/chat/completions on Cloud Run)
 * — the ONLY AI this app is allowed to call, per explicit product decision.
 * No OpenAI/Anthropic/Gemini key exists anywhere in this codebase.
 * <p>
 * Replicates CLEO (criticalasset-osint's backend/app/services/ensemble.py):
 * fan out to the water-v3 + mep-v3 specialists in parallel, then merge with
 * a synthesis pass on water-v3. Same composition, same served adapters —
 * just re-implemented here in Java since that ensemble only exists inside
 * criticalasset-osint's own Python process today, behind a browser-session-
 * only endpoint we can't call machine-to-machine (see NOTICE.md).
 * <p>
 * MAX (criticalasset-osint's mep-v4 evaluation bench: mep-v4 solo at
 * temperature 0) is wired via {@link #maxOrCleo}: the mep-v4 adapter was
 * added to the serving fleet's --lora-modules roster on 2026-07-25, matching
 * ca-osint's MAX_ROSTER. If mep-v4 ever drops off the roster again the call
 * fails fast and maxOrCleo transparently falls back to CLEO, reporting which
 * engine actually answered so the UI never mislabels the source.
 * <p>
 * Best-effort by design: auth (GCP identity token via the metadata server,
 * so this only ever works when actually running on Cloud Run — no shared
 * secret) or either specialist leg failing/timing out never throws past this
 * class; callers get an empty result and fall back to manual entry.
 */
@Service
@Slf4j
public class ExpertClient {

    private static final String METADATA_IDENTITY_URL =
            "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity?audience=";
    private static final Duration LEG_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(3);

    private static final String WATER_V3 = "criticalasset-water-v3";
    private static final String MEP_V3 = "criticalasset-mep-v3";
    private static final String MEP_V4 = "criticalasset-mep-v4";
    // The merge pass runs on the production water champion, same as CLEO —
    // tolerant of messy input, which is the point of the panel.
    private static final String SYNTHESIZER = WATER_V3;

    public static final String ENGINE_CLEO = "CLEO";
    public static final String ENGINE_MAX = "MAX";

    /** An expert reply plus which virtual model actually produced it. */
    public record ExpertAnswer(String content, String engine) {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${expert-models.base-url:}")
    private String baseUrl;

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    /**
     * CLEO: water-v3 + mep-v3 in parallel, synthesized into one reply.
     * Falls back to whichever single leg answered if the other times out or
     * errors; returns empty only if BOTH legs fail — never throws.
     */
    public java.util.Optional<String> cleo(String systemPrompt, String userContent) {
        if (!isConfigured()) return java.util.Optional.empty();

        CompletableFuture<String> waterLeg = callLegAsync(WATER_V3, systemPrompt, userContent);
        CompletableFuture<String> mepLeg = callLegAsync(MEP_V3, systemPrompt, userContent);

        String waterAnswer = await(waterLeg);
        String mepAnswer = await(mepLeg);

        if (waterAnswer != null && mepAnswer != null) {
            String synthesisPrompt = "SPECIALIST ANSWERS (each from one of our fine-tuned models, answering the "
                    + "same request independently):\n\n--- WATER specialist (" + WATER_V3 + ") ---\n" + waterAnswer
                    + "\n\n--- MEP/electrical specialist (" + MEP_V3 + ") ---\n" + mepAnswer
                    + "\n\nORIGINAL REQUEST:\n" + userContent
                    + "\n\nMerge the specialists' answers into ONE reply to the original request. Keep every "
                    + "materially different observation; where they disagree, say so. Answer in exactly the "
                    + "format the original request asked for.";
            String merged = await(callLegAsync(SYNTHESIZER, systemPrompt, synthesisPrompt));
            if (merged != null) return java.util.Optional.of(merged);
            // Synthesis call itself failed — a single healthy leg still beats nothing.
            return java.util.Optional.of(waterAnswer);
        }
        if (waterAnswer != null) return java.util.Optional.of(waterAnswer);
        if (mepAnswer != null) return java.util.Optional.of(mepAnswer);
        return java.util.Optional.empty();
    }

    /**
     * MAX: mep-v4 solo at temperature 0 — deterministic engineering review,
     * same composition as ca-osint's MAX bench. Empty if the model is off
     * the serving roster or the call fails; never throws.
     */
    public java.util.Optional<String> max(String systemPrompt, String userContent) {
        if (!isConfigured()) return java.util.Optional.empty();
        String answer = await(CompletableFuture.supplyAsync(() -> {
            try {
                return chatCompletion(MEP_V4, systemPrompt, userContent, 0.0);
            } catch (Exception e) {
                log.warn("MAX (mep-v4) call failed", e);
                return null;
            }
        }).orTimeout(LEG_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(e -> {
                    log.warn("MAX (mep-v4) timed out");
                    return null;
                }));
        return java.util.Optional.ofNullable(answer);
    }

    /**
     * MAX with CLEO fallback, tagged with whichever engine actually answered
     * — callers surface the tag so the product never claims a MAX answer
     * came from MAX when CLEO stood in.
     */
    public java.util.Optional<ExpertAnswer> maxOrCleo(String systemPrompt, String userContent) {
        java.util.Optional<String> maxAnswer = max(systemPrompt, userContent);
        if (maxAnswer.isPresent()) {
            return java.util.Optional.of(new ExpertAnswer(maxAnswer.get(), ENGINE_MAX));
        }
        return cleo(systemPrompt, userContent).map(c -> new ExpertAnswer(c, ENGINE_CLEO));
    }

    private CompletableFuture<String> callLegAsync(String model, String systemPrompt, String userContent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return chatCompletion(model, systemPrompt, userContent, 0.1);
            } catch (Exception e) {
                log.warn("expert leg {} failed", model, e);
                return null;
            }
        }).orTimeout(LEG_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(e -> {
                    log.warn("expert leg {} timed out", model);
                    return null;
                });
    }

    private static String await(CompletableFuture<String> future) {
        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private String chatCompletion(String model, String systemPrompt, String userContent, double temperature)
            throws Exception {
        String token = fetchIdentityToken();
        Map<String, Object> payload = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userContent)
                ),
                "temperature", temperature,
                "max_tokens", 800
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .timeout(LEG_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("expert HTTP " + response.statusCode() + ": " + response.body());
        }
        JsonNode root = JSON.readTree(response.body());
        return root.at("/choices/0/message/content").asText();
    }

    /**
     * Cloud Run's metadata server mints an OIDC identity token for this
     * service's own attached service account, audience-scoped to the
     * expert-serving Cloud Run URL — exactly what that service's IAM
     * invoker check expects. No shared secret, nothing to rotate. Only
     * resolves when actually running on Cloud Run/GCE; local dev has no
     * metadata server, so this fails fast and isConfigured() callers should
     * treat that as "AI drafting unavailable here" rather than an error.
     */
    private String fetchIdentityToken() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(METADATA_IDENTITY_URL + java.net.URLEncoder.encode(baseUrl, StandardCharsets.UTF_8)))
                .header("Metadata-Flavor", "Google")
                .timeout(TOKEN_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("metadata identity token HTTP " + response.statusCode());
        }
        return response.body();
    }
}
