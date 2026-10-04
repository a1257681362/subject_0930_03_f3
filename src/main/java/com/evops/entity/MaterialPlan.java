package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 工单材料计划：修复项目对某材料批号的计划用量。
 * 实际领用以 t_material_issue 记录；差异超过 reviewThresholdPercent 触发复核。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_material_plan")
public class MaterialPlan extends BaseEntity {

    private Long projectId;

    private Long batchId;

    private BigDecimal plannedQty;

    private String unit;

    /** 差异复核阈值（百分比）；为空时使用应用默认阈值 */
    private BigDecimal reviewThresholdPercent;
}
