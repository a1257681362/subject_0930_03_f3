package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 材料退料：必须关联原领用单（issueId），退料量不超过净领用量。
 */
@Data
public class MaterialReturnRequest {

    @NotNull(message = "原领用单 id 不能为空")
    private Long issueId;

    @NotNull(message = "退料数量不能为空")
    @Positive(message = "退料数量必须大于 0")
    private BigDecimal returnQty;

    @NotBlank(message = "退料操作者不能为空")
    private String operator;

    private String reason;
}
