package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 用量差异复核处理：CONFIRMED 核实无误 / ADJUSTED 已调整（台账/补料）。
 */
@Data
public class MaterialReviewHandleRequest {

    /** CONFIRMED / ADJUSTED */
    @NotBlank(message = "复核结论不能为空")
    private String result;

    @NotBlank(message = "复核人不能为空")
    private String handledBy;

    private String opinion;
}
