package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 修复工单材料领用：记录用量、使用部位、操作者。
 * planId 可空（无计划的补领）；给定时按计划做用量差异核算。
 */
@Data
public class MaterialIssueRequest {

    /** 修复项目 id，由请求路径提供 */
    private Long projectId;

    @NotNull(message = "材料批号 id 不能为空")
    private Long batchId;

    /** 关联材料计划；无计划直接领用可不传 */
    private Long planId;

    @NotNull(message = "领用用量不能为空")
    @Positive(message = "领用用量必须大于 0")
    private BigDecimal issueQty;

    @NotBlank(message = "使用部位不能为空")
    private String applyPart;

    @NotBlank(message = "操作者不能为空")
    private String operator;
}
