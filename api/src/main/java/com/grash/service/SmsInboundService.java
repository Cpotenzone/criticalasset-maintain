package com.grash.service;

import com.grash.dto.WorkOrderChangeStatusDTO;
import com.grash.dto.comment.CommentPostDTO;
import com.grash.dto.workOrder.WorkOrderAiDraftDTO;
import com.grash.dto.workOrder.WorkOrderPostDTO;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.Priority;
import com.grash.model.enums.Status;
import com.grash.repository.UserRepository;
import com.grash.security.CustomUserDetail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two-way SMS: technicians work their tickets by texting the same number
 * that notifies them — no login, no app switch. The market audit found
 * native bidirectional SMS is white space even among the AI-forward CMMS
 * leaders, so this is a differentiator, not a copy.
 * <p>
 * Grammar (case-insensitive):
 *   DONE 123 / START 123 / HOLD 123 / OPEN 123  -> status change
 *   #123 <free text>                            -> comment on that work order
 *   anything else                               -> CLEO drafts and files a new
 *                                                  work order from the text
 * <p>
 * Every action runs through the same service paths the app uses (access
 * checks, notifications, webhooks all fire); the sender is identified by
 * matching the last 10 digits of their profile phone number. Replies are
 * short confirmations Twilio sends back as the SMS response.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SmsInboundService {

    private static final Pattern STATUS_COMMAND = Pattern.compile(
            "^\\s*(done|complete|finish|start|begin|hold|pause|open|reopen)\\s*#?(\\d+)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT_COMMAND = Pattern.compile(
            "^\\s*#(\\d+)\\s+(\\S.*)$", Pattern.DOTALL);

    private final UserRepository userRepository;
    private final WorkOrderService workOrderService;
    private final CommentService commentService;
    private final WorkOrderAiDraftService workOrderAiDraftService;

    public String handle(String fromPhone, String body) {
        try {
            Optional<User> optionalUser = matchUser(fromPhone);
            if (optionalUser.isEmpty()) {
                return "This number isn't linked to a CriticalAsset Maintain account. "
                        + "Add it to your profile phone number to text commands.";
            }
            User user = optionalUser.get();
            if (body == null || body.isBlank()) {
                return helpText();
            }
            // Everything below runs AS the matched user: the entity-level
            // tenant hooks (CompanyAudit @PrePersist/@PostLoad) and audit
            // fields all read the SecurityContext, which is anonymous on
            // this webhook thread — without this, inserts would fail on the
            // not-null company column and loads would skip the tenant check.
            Authentication previous = SecurityContextHolder.getContext().getAuthentication();
            CustomUserDetail userDetail = CustomUserDetail.builder().user(user).build();
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(userDetail, "", userDetail.getAuthorities()));
            try {
                return dispatch(user, body.trim());
            } finally {
                SecurityContextHolder.getContext().setAuthentication(previous);
            }
        } catch (Exception e) {
            log.warn("inbound SMS handling failed for {}", fromPhone, e);
            return "Sorry — that didn't go through. " + helpText();
        }
    }

    private String dispatch(User user, String text) {
        Matcher statusMatcher = STATUS_COMMAND.matcher(text);
        if (statusMatcher.matches()) {
            return changeStatus(user, Long.parseLong(statusMatcher.group(2)),
                    statusMatcher.group(1).toLowerCase(Locale.ROOT));
        }
        Matcher commentMatcher = COMMENT_COMMAND.matcher(text);
        if (commentMatcher.matches()) {
            return addComment(user, Long.parseLong(commentMatcher.group(1)), commentMatcher.group(2).trim());
        }
        if (text.equalsIgnoreCase("help") || text.equals("?")) {
            return helpText();
        }
        return createWorkOrderFromText(user, text);
    }

    private Optional<User> matchUser(String fromPhone) {
        String digits = fromPhone == null ? "" : fromPhone.replaceAll("\\D", "");
        if (digits.length() < 10) return Optional.empty();
        return userRepository.findFirstByPhoneLastDigits(digits.substring(digits.length() - 10));
    }

    private String changeStatus(User user, Long workOrderId, String verb) {
        Optional<WorkOrder> optionalWorkOrder =
                workOrderService.findByIdAndCompany(workOrderId, user.getCompany().getId());
        if (optionalWorkOrder.isEmpty()) {
            return "Work order #" + workOrderId + " wasn't found.";
        }
        Status newStatus;
        switch (verb) {
            case "done":
            case "complete":
            case "finish":
                newStatus = Status.COMPLETE;
                break;
            case "start":
            case "begin":
                newStatus = Status.IN_PROGRESS;
                break;
            case "hold":
            case "pause":
                newStatus = Status.ON_HOLD;
                break;
            default:
                newStatus = Status.OPEN;
        }
        if (!optionalWorkOrder.get().canBeEditedBy(user)) {
            return "You don't have permission to update work order #" + workOrderId + ".";
        }
        WorkOrderChangeStatusDTO dto = new WorkOrderChangeStatusDTO();
        dto.setStatus(newStatus);
        workOrderService.changeStatus(dto, workOrderId, user, "sms");
        return "Work order #" + workOrderId + " is now " + newStatus.name().replace('_', ' ') + ".";
    }

    private String addComment(User user, Long workOrderId, String content) {
        Optional<WorkOrder> optionalWorkOrder =
                workOrderService.findByIdAndCompany(workOrderId, user.getCompany().getId());
        if (optionalWorkOrder.isEmpty()) {
            return "Work order #" + workOrderId + " wasn't found.";
        }
        CommentPostDTO commentPost = new CommentPostDTO();
        commentPost.setWorkOrder(optionalWorkOrder.get());
        commentPost.setContent(content);
        commentService.create(commentPost, user);
        return "Comment added to work order #" + workOrderId + ".";
    }

    /**
     * The NL-intake path: free text becomes a CLEO-drafted, immediately
     * filed work order. If CLEO can't draft (models unreachable), the raw
     * text still becomes a ticket — losing a report is never acceptable.
     */
    private String createWorkOrderFromText(User user, String text) {
        WorkOrderAiDraftDTO draft = workOrderAiDraftService.draftFromText(text);
        WorkOrderPostDTO workOrderPost = new WorkOrderPostDTO();
        if (draft.isSuccess()) {
            workOrderPost.setTitle(draft.getTitle());
            workOrderPost.setDescription(draft.getDescription());
            workOrderPost.setPriority(Arrays.stream(Priority.values())
                    .filter(priority -> priority.name().equalsIgnoreCase(draft.getPriority()))
                    .findFirst().orElse(Priority.MEDIUM));
        } else {
            workOrderPost.setTitle(text.length() <= 80 ? text : text.substring(0, 77) + "…");
            workOrderPost.setDescription(text);
            workOrderPost.setPriority(Priority.MEDIUM);
        }
        WorkOrder created = workOrderService.createByUser(workOrderPost, user);
        return "Created work order #" + created.getId() + ": " + created.getTitle()
                + " (" + created.getPriority() + ")."
                + (draft.isSuccess() ? " Drafted by CLEO." : "");
    }

    private String helpText() {
        return "Commands: DONE 123, START 123, HOLD 123, '#123 your note' to comment, "
                + "or describe a new problem and AI files the ticket.";
    }
}
