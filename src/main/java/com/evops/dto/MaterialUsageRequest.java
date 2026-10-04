package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 登记领用材料的实际用量，触发计划 vs 实际差异核算。
 * 领用单 id 由请求路径提供。
 */
@Data
public class MaterialUsageRequest {

    @NotNull(message = "实际用量不能为空")
    @Positive(message = "实际用量必须大于 0")
    private BigDecimal actualQty;
}
