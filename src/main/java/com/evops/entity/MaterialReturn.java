package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 材料退料单：必须关联原领用单（一张领用单最多退一次）。
 * 退料在同一事务回补批号可用库存。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_material_return")
public class MaterialReturn extends BaseEntity {

    private String returnNo;

    /** 原领用单 id */
    private Long issueId;

    private Long projectId;

    private Long batchId;

    private BigDecimal returnQty;

    private String unit;

    /** 退料操作者 */
    private String operator;

    private String reason;

    private LocalDateTime returnedAt;
}
