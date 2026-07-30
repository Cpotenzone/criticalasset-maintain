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
 * base + LoRA adapters, OpenAI-compatible /v1/chat/completions) — the ONLY AI
 * this app is allowed to call, per explicit product decision. No
 * OpenAI/Anthropic/Gemini key exists anywhere in this codebase.
 * <p>
 * Replicates CLEO (criticalasset-osint's backend/app/services/ensemble.py):
 * fan out to the water + MEP specialists in parallel, then merge with a
 * synthesis pass on the water champion. Same composition, same served
 * adapters — just re-implemented here in Java since that ensemble only exists
 * inside criticalasset-osint's own Python process today, behind a
 * browser-session-only endpoint we can't call machine-to-machine (see
 * NOTICE.md).
 * <p>
 * MODEL NAMES ARE STABLE ROLE ALIASES ({@code cleo-water}, {@code max-mep}),
 * not pinned versions. This class used to name {@code criticalasset-water-v3}
 * and friends directly, which meant every champion promotion needed a code
 * change here, and an id drifting away from the served roster silently emptied
 * a lane instead of failing. The AWS serving repoints the alias server-side;
 * nothing here changes again.
 * <p>
 * AUTH FOLLOWS THE URL. Post-cutover (2026-07-30) the serving lives in
 * CriticalAsset's own AWS account behind a static bearer key
 * ({@code CA_EXPERTS_KEY}); the retired Cloud Run endpoint needed a GCP
 * identity token from the metadata server, which only ever resolved when
 * actually running on Cloud Run. Deriving the choice from the configured
 * base-url (see {@link #authToken()}) means a rollback to Cloud Run is a
 * one-line config change that cannot leave the auth mode mismatched with the
 * endpoint.
 * <p>
 * Best-effort by design: auth or either specialist leg failing/timing out never
 * throws past this class; callers get an empty result and fall back to manual
 * entry.
 */
@Service
@Slf4j
public class ExpertClient {

    private static final String METADATA_IDENTITY_URL =
            "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity?audience=";
    private static final Duration LEG_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(3);

    // Stable ROLE ALIASES, matching ca-osint's app/services/model_roles.py.
    // Never pin a version here: the serving repoints an alias when a better
    // adapter wins its exam, and a hardcoded version id that drifts off the
    // served roster 404s and empties this lane silently.
    private static final String CLEO_WATER = "cleo-water";
    private static final String CLEO_MEP = "cleo-mep";
    private static final String MAX_MEP = "max-mep";
    // The merge pass runs on the water intake role, same as CLEO — tolerant of
    // messy input, which is the point of the panel.
    private static final String SYNTHESIZER = CLEO_WATER;

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

    /** Static bearer key for the AWS serving. Empty when pointed at Cloud Run. */
    @Value("${expert-models.api-key:}")
    private String apiKey;

    public boolean isConfigured() {
        if (baseUrl == null || baseUrl.isBlank()) return false;
        // A bearer-auth endpoint with no key can never succeed. Say "not
        // configured" so callers fall back to manual entry immediately, rather
        // than firing three requests per draft that all 401.
        return !usesStaticKey() || !(apiKey == null || apiKey.isBlank());
    }

    /**
     * Does this endpoint take a static bearer key, or a GCP identity token?
     * Derived from the URL so the two can never be configured inconsistently:
     * only the retired Cloud Run serving uses identity tokens.
     */
    private boolean usesStaticKey() {
        return baseUrl != null && !baseUrl.contains(".run.app");
    }

    /**
     * CLEO: the water + MEP intake roles in parallel, synthesized into one reply.
     * Falls back to whichever single leg answered if the other times out or
     * errors; returns empty only if BOTH legs fail — never throws.
     */
    public java.util.Optional<String> cleo(String systemPrompt, String userContent) {
        if (!isConfigured()) return java.util.Optional.empty();

        CompletableFuture<String> waterLeg = callLegAsync(CLEO_WATER, systemPrompt, userContent);
        CompletableFuture<String> mepLeg = callLegAsync(CLEO_MEP, systemPrompt, userContent);

        String waterAnswer = await(waterLeg);
        String mepAnswer = await(mepLeg);

        if (waterAnswer != null && mepAnswer != null) {
            String synthesisPrompt = "SPECIALIST ANSWERS (each from one of our fine-tuned models, answering the "
                    + "same request independently):\n\n--- WATER specialist (" + CLEO_WATER + ") ---\n" + waterAnswer
                    + "\n\n--- MEP/electrical specialist (" + CLEO_MEP + ") ---\n" + mepAnswer
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
     * MAX: the electrical scoring role solo at temperature 0 — deterministic
     * engineering review, same composition as ca-osint's MAX bench. Empty if
     * the alias is unserved or the call fails; never throws.
     */
    public java.util.Optional<String> max(String systemPrompt, String userContent) {
        if (!isConfigured()) return java.util.Optional.empty();
        String answer = await(CompletableFuture.supplyAsync(() -> {
            try {
                return chatCompletion(MAX_MEP, systemPrompt, userContent, 0.0);
            } catch (Exception e) {
                log.warn("MAX ({}) call failed", MAX_MEP, e);
                return null;
            }
        }).orTimeout(LEG_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(e -> {
                    log.warn("MAX ({}) timed out", MAX_MEP);
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
        String token = authToken();
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
     * The bearer credential for the configured endpoint.
     * <p>
     * AWS serving: the static {@code CA_EXPERTS_KEY}. Unlike the identity-token
     * path this works off-GCP, so AI drafting now functions in local dev and
     * anywhere else the key is present — the metadata server was the reason it
     * only ever worked on Cloud Run.
     */
    private String authToken() throws Exception {
        if (usesStaticKey()) {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException(
                        "expert-models.api-key (CA_EXPERTS_KEY) is not set, and "
                                + baseUrl + " requires a static bearer key");
            }
            return apiKey;
        }
        return fetchIdentityToken();
    }

    /**
     * Cloud Run's metadata server mints an OIDC identity token for this
     * service's own attached service account, audience-scoped to the
     * expert-serving Cloud Run URL — exactly what that service's IAM
     * invoker check expects. No shared secret, nothing to rotate. Only
     * resolves when actually running on Cloud Run/GCE; local dev has no
     * metadata server, so this fails fast and isConfigured() callers should
     * treat that as "AI drafting unavailable here" rather than an error.
     * <p>
     * Retired path — used only while {@code expert-models.base-url} still points
     * at a {@code *.run.app} host.
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
