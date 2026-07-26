package com.grash.dto.workOrder;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Free text to turn into a draft work order — a voice transcript or typed description")
public class WorkOrderAiDraftRequestDTO {
    @NotBlank
    @Schema(description = "Technician's free-text description or voice transcript",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String text;
}
