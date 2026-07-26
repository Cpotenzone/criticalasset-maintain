package com.grash.service;

import com.grash.dto.AiInsightsDTO;
import com.grash.model.Comment;
import com.grash.model.Task;
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
 * CLEO-written work-order briefing: everything known about the ticket
 * (fields, tasks, comments) plus the same asset's recent history, condensed
 * into a supervisor-readable summary with recurrence flags and next steps.
 * The market audit's #2 feature (MaintainX CoPilot / UpKeep Nova both lead
 * with WO summarization) — built on our own served models only.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkOrderAiInsightsService {

    private static final String SYSTEM_PROMPT =
            "You are the maintenance-operations AI inside CriticalAsset Maintain, a CMMS. You brief supervisors "
                    + "and technicians on work orders. Write plain text (no markdown syntax) in EXACTLY these four "
                    + "titled sections, each 1-3 short sentences or bullets:\n"
                    + "SUMMARY: what this work order is and where it stands.\n"
                    + "HISTORY SIGNAL: what this asset's past work orders suggest — call out repeat failures "
                    + "explicitly; if there is no meaningful history, say so.\n"
                    + "RISKS: anything overdue, blocked, safety-relevant, or trending worse.\n"
                    + "NEXT STEPS: the most useful concrete actions, as short bullets starting with '- '.\n"
                    + "GROUNDING RULES (mandatory): use ONLY the data provided; never invent equipment, causes, "
                    + "dates, or history; if data is too thin for a section, write 'Not enough data.' for it.";

    private static final SimpleDateFormat DATE = new SimpleDateFormat("yyyy-MM-dd");
    private static final int ASSET_HISTORY_LIMIT = 10;

    private final ExpertClient expertClient;
    private final WorkOrderService workOrderService;
    private final TaskService taskService;
    private final CommentService commentService;

    public AiInsightsDTO generate(WorkOrder workOrder) {
        if (!expertClient.isConfigured()) {
            return AiInsightsDTO.failed();
        }
        try {
            String context = buildContext(workOrder);
            return expertClient.cleo(SYSTEM_PROMPT, context)
                    .map(text -> new AiInsightsDTO(true, text.trim(), ExpertClient.ENGINE_CLEO))
                    .orElse(AiInsightsDTO.failed());
        } catch (Exception e) {
            log.warn("work order ai-insights failed for WO {}", workOrder.getId(), e);
            return AiInsightsDTO.failed();
        }
    }

    private String buildContext(WorkOrder workOrder) {
        StringBuilder sb = new StringBuilder();
        sb.append("WORK ORDER #").append(workOrder.getId());
        if (workOrder.getTitle() != null) sb.append(": ").append(workOrder.getTitle());
        sb.append("\nStatus: ").append(workOrder.getStatus());
        sb.append(" | Priority: ").append(workOrder.getPriority());
        sb.append(" | Created: ").append(fmt(workOrder.getCreatedAt()));
        if (workOrder.getDueDate() != null) {
            sb.append(" | Due: ").append(fmt(workOrder.getDueDate()));
            if (workOrder.getCompletedOn() == null && workOrder.getDueDate().before(new Date())) {
                sb.append(" (OVERDUE)");
            }
        }
        if (workOrder.getCompletedOn() != null) sb.append(" | Completed: ").append(fmt(workOrder.getCompletedOn()));
        if (workOrder.getDescription() != null && !workOrder.getDescription().isBlank()) {
            sb.append("\nDescription: ").append(truncate(workOrder.getDescription(), 800));
        }
        if (workOrder.getAsset() != null) {
            sb.append("\nAsset: ").append(workOrder.getAsset().getName());
            if (workOrder.getAsset().getModel() != null) sb.append(" (").append(workOrder.getAsset().getModel()).append(")");
        }
        if (workOrder.getLocation() != null) sb.append("\nLocation: ").append(workOrder.getLocation().getName());
        if (workOrder.getPrimaryUser() != null) {
            sb.append("\nPrimary assignee: ").append(workOrder.getPrimaryUser().getFirstName())
                    .append(" ").append(workOrder.getPrimaryUser().getLastName());
        }
        sb.append("\nSource: ").append(workOrder.isReactive() ? "reactive request" : "preventive maintenance schedule");

        List<Task> tasks = taskService.findByWorkOrder(workOrder.getId());
        if (!tasks.isEmpty()) {
            sb.append("\n\nTASKS (").append(tasks.size()).append("):");
            tasks.stream().limit(15).forEach(task -> {
                sb.append("\n- ");
                if (task.getTaskBase() != null && task.getTaskBase().getLabel() != null) {
                    sb.append(task.getTaskBase().getLabel());
                }
                if (task.getValue() != null && !task.getValue().isBlank()) {
                    sb.append(" -> ").append(truncate(task.getValue(), 120));
                }
                if (task.getNotes() != null && !task.getNotes().isBlank()) {
                    sb.append(" (notes: ").append(truncate(task.getNotes(), 120)).append(")");
                }
            });
        }

        List<Comment> comments = commentService.findByWorkOrder(workOrder.getId());
        if (!comments.isEmpty()) {
            sb.append("\n\nCOMMENTS (").append(comments.size()).append(", oldest first):");
            comments.stream()
                    .sorted(Comparator.comparing(Comment::getCreatedAt,
                            Comparator.nullsFirst(Comparator.naturalOrder())))
                    .limit(15)
                    .forEach(comment -> sb.append("\n- [").append(fmt(comment.getCreatedAt())).append("] ")
                            .append(truncate(comment.getFormattedContent(), 250)));
        }

        if (workOrder.getAsset() != null) {
            List<WorkOrder> history = workOrderService.findByAsset(workOrder.getAsset().getId()).stream()
                    .filter(other -> !other.getId().equals(workOrder.getId()))
                    .sorted(Comparator.comparing(WorkOrder::getCreatedAt,
                            Comparator.nullsFirst(Comparator.reverseOrder())))
                    .limit(ASSET_HISTORY_LIMIT)
                    .collect(Collectors.toList());
            if (!history.isEmpty()) {
                sb.append("\n\nSAME-ASSET WORK ORDER HISTORY (most recent ").append(history.size()).append("):");
                history.forEach(other -> {
                    sb.append("\n- #").append(other.getId()).append(" [").append(fmt(other.getCreatedAt())).append("] ")
                            .append(truncate(other.getTitle(), 100))
                            .append(" — ").append(other.getStatus())
                            .append(", ").append(other.getPriority());
                    if (other.getCompletedOn() != null) sb.append(", completed ").append(fmt(other.getCompletedOn()));
                });
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
