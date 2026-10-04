package com.evops.constant;

/**
 * 文物修复台账各业务表的状态与动作常量。
 * 库中一律存字符串码值，禁止用物理删除表达"撤销"。
 */
public final class MuseumConstants {

    private MuseumConstants() {
    }

    /** 文物档案 / 入藏登记状态 */
    public static final String ACTIVE = "ACTIVE";
    public static final String REVOKED = "REVOKED";

    /** 保存等级：一 / 二 / 三级 */
    public static final String GRADE_ONE = "ONE";
    public static final String GRADE_TWO = "TWO";
    public static final String GRADE_THREE = "THREE";

    /** 修复项目状态：立项 -> 修复中 -> 修复完成 -> 已验收（作废为旁路终态） */
    public static final String PROJECT_PLANNED = "PLANNED";
    public static final String PROJECT_REPAIRING = "REPAIRING";
    public static final String PROJECT_REPAIRED = "REPAIRED";
    public static final String PROJECT_ACCEPTED = "ACCEPTED";
    public static final String PROJECT_CANCELLED = "CANCELLED";

    /** 借还状态 */
    public static final String LOAN_LENT = "LENT";
    public static final String LOAN_RETURNED = "RETURNED";
    public static final String LOAN_OVERDUE = "OVERDUE";

    /** 验收结论 */
    public static final String ACCEPT_PASS = "PASS";
    public static final String ACCEPT_CONDITIONAL = "CONDITIONAL";

    /** 台账流水动作 */
    public static final String ACTION_INTAKE = "INTAKE";
    public static final String ACTION_LOAN_OUT = "LOAN_OUT";
    public static final String ACTION_RETURN = "RETURN";
    public static final String ACTION_REPAIR_START = "REPAIR_START";
    public static final String ACTION_REPAIR_STAGE = "REPAIR_STAGE";
    public static final String ACTION_REPAIR_FINISH = "REPAIR_FINISH";
    public static final String ACTION_ACCEPT = "ACCEPT";
    public static final String ACTION_REVOKE = "REVOKE";
    public static final String ACTION_CANCEL = "CANCEL";
    /** 影像归档：批量上传确认 / 影像作废 / 权限收紧 / 权限放开 */
    public static final String ACTION_IMAGE_UPLOAD = "IMAGE_UPLOAD";
    public static final String ACTION_IMAGE_VOID = "IMAGE_VOID";
    public static final String ACTION_POLICY_TIGHTEN = "PERMIT_TIGHTEN";
    public static final String ACTION_POLICY_RELAX = "PERMIT_RELAX";

    /** 起步幂等锁固定主键，全表只允许一行 */
    public static final int BOOTSTRAP_LOCK_ID = 1;

    // --------------------------------------------------------------------
    // museum-2 文物多角度影像归档
    // --------------------------------------------------------------------

    /** 影像视角：正面 / 背面 / 底部 / 细部 */
    public static final String VIEW_FRONT = "FRONT";
    public static final String VIEW_BACK = "BACK";
    public static final String VIEW_BOTTOM = "BOTTOM";
    public static final String VIEW_DETAIL = "DETAIL";

    /** 影像引用 / 拍摄批次状态：有效 / 已作废（作废不是删除） */
    public static final String IMAGE_ACTIVE = "ACTIVE";
    public static final String IMAGE_VOID = "VOID";
    public static final String BATCH_CONFIRMED = "CONFIRMED";

    /** 权限策略表固定主键，全表只允许一行 */
    public static final int POLICY_ID = 1;

    /** 岗位（下载脱敏按岗位区分）：修复师 / 馆藏保管员 / 观摩访客 */
    public static final String ROLE_RESTORER = "RESTORER";
    public static final String ROLE_CURATOR = "CURATOR";
    public static final String ROLE_VIEWER = "VIEWER";

    // --------------------------------------------------------------------
    // museum-3 修复材料批号与用量核算
    // --------------------------------------------------------------------

    /** 材料类别：颜料 / 黏合剂 / 保护剂 */
    public static final String MATERIAL_PIGMENT = "PIGMENT";
    public static final String MATERIAL_ADHESIVE = "ADHESIVE";
    public static final String MATERIAL_PROTECTANT = "PROTECTANT";

    /** 材料批号库存状态：在库可用 / 已耗尽 / 已过期 */
    public static final String BATCH_ACTIVE = "ACTIVE";
    public static final String BATCH_EXHAUSTED = "EXHAUSTED";
    public static final String BATCH_EXPIRED = "EXPIRED";

    /** 领用单状态：已领用 / 已退料（部分或全部） */
    public static final String ISSUE_ISSUED = "ISSUED";
    public static final String ISSUE_RETURNED = "RETURNED";

    /** 复核原因 / 状态：用量差异超阈值；待复核 / 已核实 / 已调整 */
    public static final String REVIEW_DAT_DIFF = "DAT_DIFF";
    public static final String REVIEW_PENDING = "PENDING";
    public static final String REVIEW_CONFIRMED = "CONFIRMED";
    public static final String REVIEW_ADJUSTED = "ADJUSTED";

    /** 台账流水动作：材料入库 / 领用 / 登记实际用量 / 退料 / 触发复核 / 复核处理 */
    public static final String ACTION_MATERIAL_INBOUND = "MATERIAL_INBOUND";
    public static final String ACTION_MATERIAL_ISSUE = "MATERIAL_ISSUE";
    public static final String ACTION_MATERIAL_USED = "MATERIAL_USED";
    public static final String ACTION_MATERIAL_RETURN = "MATERIAL_RETURN";
    public static final String ACTION_MATERIAL_REVIEW = "MATERIAL_REVIEW";
    public static final String ACTION_MATERIAL_REVIEW_HANDLE = "MATERIAL_REVIEW_HANDLE";

    /** 材料台账事件使用的占位文物 id（无具体文物归属，如入库） */
    public static final long MATERIAL_GLOBAL_SCOPE = 0L;
}
