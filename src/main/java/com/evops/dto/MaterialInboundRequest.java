package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 修复材料按批号入库请求：颜料 / 黏合剂 / 保护剂。
 */
@Data
public class MaterialInboundRequest {

    /** 材料批号，全库唯一 */
    @NotBlank(message = "材料批号不能为空")
    private String batchNo;

    @NotBlank(message = "材料名称不能为空")
    private String materialName;

    /** PIGMENT 颜料 / ADHESIVE 黏合剂 / PROTECTANT 保护剂 */
    @NotBlank(message = "材料类别不能为空")
    private String category;

    private String specification;

    private String manufacturer;

    @NotBlank(message = "计量单位不能为空")
    private String unit;

    @NotNull(message = "入库数量不能为空")
    @Positive(message = "入库数量必须大于 0")
    private BigDecimal inboundQty;

    private LocalDate productionDate;

    @NotNull(message = "到期日期不能为空")
    private LocalDate expireDate;
}
