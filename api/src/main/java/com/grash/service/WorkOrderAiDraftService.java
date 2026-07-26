package com.grash.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.dto.workOrder.WorkOrderAiDraftDTO;
import com.grash.model.enums.Priority;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a technician's free-text description (typed, or transcribed from a
 * voice note on-device — CLEO never sees audio, see ExpertClient) into a
 * draft work order via CLEO. Assistive only: the technician reviews and can
 * edit every field before the ticket is actually created, same doctrine as
 * every AI-forward CMMS in the market audit — none of them auto-submit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkOrderAiDraftService {

    private static final String SYSTEM_PROMPT =
            "You are a strict JSON extraction tool for a CMMS maintenance ticket system. Given a maintenance "
                    + "technician's free-text description or voice transcript, extract a structured work order "
                    + "draft. Respond with ONLY a JSON object, no markdown, no prose, matching exactly this shape: "
                    + "{\"title\": string (max 80 chars, specific — what and where), "
                    + "\"description\": string (the full detail, cleaned up but not embellished), "
                    + "\"priority\": one of NONE, LOW, MEDIUM, HIGH}. "
                    + "GROUNDING RULES (mandatory): never invent equipment, locations, dates, or facts not stated "
                    + "in the input; if urgency isn't stated or implied, default priority to MEDIUM; if the input "
                    + "is too vague to produce a meaningful title, use \"Maintenance request\" as the title rather "
                    + "than guessing specifics.";

    // Model output is meant to be pure JSON, but strip code fences defensively —
    // instruction-following on a smaller fine-tuned model isn't 100% reliable.
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);

    private final ExpertClient expertClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WorkOrderAiDraftDTO draftFromText(String text) {
        if (!expertClient.isConfigured()) {
            return new WorkOrderAiDraftDTO(false, null, null, null);
        }
        Optional<String> raw = expertClient.cleo(SYSTEM_PROMPT, text);
        if (raw.isEmpty()) {
            return new WorkOrderAiDraftDTO(false, null, null, null);
        }
        try {
            return parse(raw.get());
        } catch (Exception e) {
            log.warn("failed to parse CLEO work order draft response: {}", raw.get(), e);
            return new WorkOrderAiDraftDTO(false, null, null, null);
        }
    }

    private WorkOrderAiDraftDTO parse(String content) throws Exception {
        Matcher matcher = JSON_OBJECT.matcher(content);
        if (!matcher.find()) {
            throw new IllegalArgumentException("no JSON object found in model response");
        }
        JsonNode node = objectMapper.readTree(matcher.group());
        String title = node.path("title").asText(null);
        String description = node.path("description").asText(null);
        String priorityRaw = node.path("priority").asText("MEDIUM");
        String priority = Arrays.stream(Priority.values())
                .map(Enum::name)
                .filter(p -> p.equalsIgnoreCase(priorityRaw))
                .findFirst()
                .orElse(Priority.MEDIUM.name());
        if (title == null || title.isBlank()) {
            title = "Maintenance request";
        }
        return new WorkOrderAiDraftDTO(true, title, description, priority);
    }
}
