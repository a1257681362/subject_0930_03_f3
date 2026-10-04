package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用量差异复核任务：计划用量与实际用量差异超阈值时生成。
 *
 * <p>按 (planId, reason) 数据库唯一：定时任务在应用重启后重复扫描，
 * 同一计划同一原因也只保留一条复核记录，不重复写。
 * {@code issueOperator} 冗余领用操作者，重扫组装时不丢操作者。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_material_review")
public class MaterialReview extends BaseEntity {

    private String reviewNo;

    private Long planId;

    private Long projectId;

    private Long issueId;

    private Long batchId;

    /** DAT_DIFF 用量差异超阈值 */
    private String reason;

    private BigDecimal plannedQty;

    private BigDecimal actualQty;

    /** 实际相对计划的偏差百分比（绝对值） */
    private BigDecimal diffPercent;

    private BigDecimal thresholdPercent;

    private String applyPart;

    /** 领用操作者快照（不丢操作者） */
    private String issueOperator;

    /** PENDING 待复核 / CONFIRMED 已核实 / ADJUSTED 已调整 */
    private String status;

    private String handledBy;

    private LocalDateTime handledAt;

    private String handleOpinion;

    private LocalDateTime detectedAt;
}
