package com.grash.dto.workOrder;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "AI-drafted work order fields for the technician to review before creating the ticket")
public class WorkOrderAiDraftDTO {
    @Schema(description = "Whether a draft could be produced (false if CLEO is unreachable/unconfigured)")
    private boolean success;
    @Schema(description = "Suggested work order title")
    private String title;
    @Schema(description = "Suggested work order description")
    private String description;
    @Schema(description = "Suggested priority: NONE, LOW, MEDIUM, or HIGH")
    private String priority;
}
