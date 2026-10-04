package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 修复材料批号库存：颜料 / 黏合剂 / 保护剂按批号入库。
 * 批号全库唯一；availableQty 随领用扣减、退料回补；过期/耗尽只流转状态，不删行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_material_batch")
public class MaterialBatch extends BaseEntity {

    /** 材料批号，全库唯一 */
    private String batchNo;

    private String materialName;

    /** PIGMENT 颜料 / ADHESIVE 黏合剂 / PROTECTANT 保护剂 */
    private String category;

    private String specification;

    private String manufacturer;

    /** 计量单位，如 g / ml */
    private String unit;

    /** 入库总量（不可变） */
    private BigDecimal inboundQty;

    /** 当前可用库存 */
    private BigDecimal availableQty;

    private LocalDate productionDate;

    private LocalDateTime inboundAt;

    /** 到期日期，到期当天即不可领用 */
    private LocalDate expireDate;

    /** ACTIVE 在库可用 / EXHAUSTED 已耗尽 / EXPIRED 已过期 */
    private String status;
}
