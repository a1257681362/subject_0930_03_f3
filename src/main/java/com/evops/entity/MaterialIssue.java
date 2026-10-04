package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 材料领用单：修复工单领用，记录领用（计划）用量、实际用量、使用部位、操作者。
 *
 * <p>{@code materialName}/{@code batchNo} 是领用时刻的不可变快照，
 * 之后材料批号变更（新批次入库、旧批耗尽）不影响历史工单的展示与核算。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_material_issue")
public class MaterialIssue extends BaseEntity {

    private String issueNo;

    private Long projectId;

    private Long batchId;

    /** 关联的材料计划；无计划直接领用（补领）时为空 */
    private Long planId;

    /** 领用量（账面出库量） */
    private BigDecimal issueQty;

    /** 实际用量，登记前为空；退料量 = issueQty - actualQty 的约束在业务层校验 */
    private BigDecimal actualQty;

    /** 累计退料量 */
    private BigDecimal returnedQty;

    private String unit;

    /** 使用部位 */
    private String applyPart;

    /** 操作者（领用/实际使用人），不可为空 */
    private String operator;

    /** 领用时刻材料名称快照 */
    private String materialName;

    /** 领用时刻批号快照 */
    private String batchNo;

    private LocalDateTime issuedAt;

    /** 实际用量登记时间 */
    private LocalDateTime usedAt;

    /** ISSUED 已领用 / RETURNED 已发生退料 */
    private String status;
}
