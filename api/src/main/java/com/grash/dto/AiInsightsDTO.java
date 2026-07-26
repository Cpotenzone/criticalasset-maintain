package com.grash.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "AI-generated insights for a work order or asset, produced by CriticalAsset's own expert models")
public class AiInsightsDTO {
    @Schema(description = "Whether insights were generated")
    private boolean success;
    @Schema(description = "The insights text, structured in short titled sections")
    private String insights;
    @Schema(description = "Which virtual model answered: CLEO or MAX")
    private String engine;

    public static AiInsightsDTO failed() {
        return new AiInsightsDTO(false, null, null);
    }
}
