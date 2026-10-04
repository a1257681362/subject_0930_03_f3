package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 工单材料计划：项目对某材料批号的计划用量与差异复核阈值。
 */
@Data
public class MaterialPlanRequest {

    /** 修复项目 id，由请求路径提供 */
    private Long projectId;

    @NotNull(message = "材料批号 id 不能为空")
    private Long batchId;

    @NotNull(message = "计划用量不能为空")
    @Positive(message = "计划用量必须大于 0")
    private BigDecimal plannedQty;

    /** 差异复核阈值（百分比），为空时使用系统默认阈值 */
    @Positive(message = "复核阈值必须大于 0")
    private BigDecimal reviewThresholdPercent;
}
