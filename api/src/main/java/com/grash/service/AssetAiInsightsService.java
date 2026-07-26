package com.grash.service;

import com.grash.dto.AiInsightsDTO;
import com.grash.model.Asset;
import com.grash.model.AssetDowntime;
import com.grash.model.WorkOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MAX-graded asset health: the deterministic mep-v4 bench reviews an asset's
 * full work-order + downtime record and returns a health read, recurring
 * failure patterns, and a preventive-maintenance cadence recommendation —
 * the Fiix Foresight / Limble "asset insights" category, on our own models.
 * Falls back to CLEO (and says so in {@code engine}) if mep-v4 is off the
 * serving roster.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssetAiInsightsService {

    private static final String SYSTEM_PROMPT =
            "You are MAX, CriticalAsset's mission-assurance engineering reviewer inside the CriticalAsset Maintain "
                    + "CMMS. You assess one asset from its maintenance record. Write plain text (no markdown "
                    + "syntax) in EXACTLY these four titled sections, each 1-3 short sentences or bullets:\n"
                    + "HEALTH: overall condition read from the record (stable / degrading / insufficient data) and why.\n"
                    + "RECURRING ISSUES: failure patterns that repeat across work orders; name the pattern and count. "
                    + "If none repeat, say so.\n"
                    + "PM RECOMMENDATION: whether current preventive coverage looks right, and a concrete cadence "
                    + "suggestion (e.g. 'monthly filter inspection') tied to what actually failed.\n"
                    + "WATCH ITEMS: the few things a supervisor should keep an eye on, as short bullets starting with '- '.\n"
                    + "GROUNDING RULES (mandatory): use ONLY the data provided; never invent failures, dates, "
                    + "specifications, or history; if data is too thin for a section, write 'Not enough data.' for it.";

    private static final SimpleDateFormat DATE = new SimpleDateFormat("yyyy-MM-dd");
    private static final int HISTORY_LIMIT = 25;

    private final ExpertClient expertClient;
    private final WorkOrderService workOrderService;
    private final AssetDowntimeService assetDowntimeService;

    public AiInsightsDTO generate(Asset asset) {
        if (!expertClient.isConfigured()) {
            return AiInsightsDTO.failed();
        }
        try {
            String context = buildContext(asset);
            return expertClient.maxOrCleo(SYSTEM_PROMPT, context)
                    .map(answer -> new AiInsightsDTO(true, answer.content().trim(), answer.engine()))
                    .orElse(AiInsightsDTO.failed());
        } catch (Exception e) {
            log.warn("asset ai-insights failed for asset {}", asset.getId(), e);
            return AiInsightsDTO.failed();
        }
    }

    private String buildContext(Asset asset) {
        StringBuilder sb = new StringBuilder();
        sb.append("ASSET: ").append(asset.getName());
        if (asset.getModel() != null) sb.append(" | Model: ").append(asset.getModel());
        if (asset.getManufacturer() != null) sb.append(" | Manufacturer: ").append(asset.getManufacturer());
        if (asset.getSerialNumber() != null) sb.append(" | Serial: ").append(asset.getSerialNumber());
        if (asset.getCategory() != null) sb.append(" | Category: ").append(asset.getCategory().getName());
        sb.append("\nStatus: ").append(asset.getStatus());
        if (asset.getLocation() != null) sb.append(" | Location: ").append(asset.getLocation().getName());
        if (asset.getInServiceDate() != null) sb.append(" | In service since: ").append(fmt(asset.getInServiceDate()));
        if (asset.getWarrantyExpirationDate() != null) {
            sb.append(" | Warranty until: ").append(fmt(asset.getWarrantyExpirationDate()));
        }
        if (asset.getDescription() != null && !asset.getDescription().isBlank()) {
            sb.append("\nDescription: ").append(truncate(asset.getDescription(), 400));
        }

        List<WorkOrder> history = workOrderService.findByAsset(asset.getId()).stream()
                .sorted(Comparator.comparing(WorkOrder::getCreatedAt,
                        Comparator.nullsFirst(Comparator.reverseOrder())))
                .limit(HISTORY_LIMIT)
                .collect(Collectors.toList());
        long reactive = history.stream().filter(WorkOrder::isReactive).count();
        sb.append("\n\nWORK ORDER HISTORY (most recent ").append(history.size())
                .append("; ").append(reactive).append(" reactive / ")
                .append(history.size() - reactive).append(" preventive):");
        if (history.isEmpty()) {
            sb.append("\n(none recorded)");
        }
        for (WorkOrder workOrder : history) {
            sb.append("\n- #").append(workOrder.getId())
                    .append(" [").append(fmt(workOrder.getCreatedAt())).append("] ")
                    .append(truncate(workOrder.getTitle(), 100))
                    .append(" — ").append(workOrder.getStatus())
                    .append(", ").append(workOrder.getPriority())
                    .append(workOrder.isReactive() ? ", reactive" : ", preventive");
            if (workOrder.getCompletedOn() != null) {
                sb.append(", completed ").append(fmt(workOrder.getCompletedOn()));
            }
            if (workOrder.getDescription() != null && !workOrder.getDescription().isBlank()) {
                sb.append(" — ").append(truncate(workOrder.getDescription(), 150));
            }
        }

        List<AssetDowntime> downtimes = assetDowntimeService.findByAsset(asset.getId()).stream()
                .sorted(Comparator.comparing(AssetDowntime::getStartsOn,
                        Comparator.nullsFirst(Comparator.reverseOrder())))
                .limit(15)
                .collect(Collectors.toList());
        if (!downtimes.isEmpty()) {
            long totalHours = downtimes.stream().mapToLong(AssetDowntime::getDuration).sum() / 3600;
            sb.append("\n\nDOWNTIME EVENTS (most recent ").append(downtimes.size())
                    .append(", ~").append(totalHours).append("h total):");
            for (AssetDowntime downtime : downtimes) {
                sb.append("\n- [").append(fmt(downtime.getStartsOn())).append("] ")
                        .append(downtime.getDuration() / 3600).append("h ")
                        .append(downtime.getDuration() % 3600 / 60).append("m");
            }
        }
        return sb.toString();
    }

    private static String fmt(Date date) {
        return date == null ? "unknown" : DATE.format(date);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
