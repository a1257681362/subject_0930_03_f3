package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.dto.MaterialInboundRequest;
import com.evops.dto.MaterialIssueRequest;
import com.evops.dto.MaterialPlanRequest;
import com.evops.dto.MaterialReturnRequest;
import com.evops.entity.MaterialBatch;
import com.evops.entity.MaterialIssue;
import com.evops.entity.MaterialPlan;
import com.evops.entity.MaterialReturn;
import com.evops.entity.MaterialReview;
import com.evops.entity.RepairProject;
import com.evops.mapper.MaterialBatchMapper;
import com.evops.mapper.MaterialIssueMapper;
import com.evops.mapper.MaterialPlanMapper;
import com.evops.mapper.MaterialReturnMapper;
import com.evops.mapper.MaterialReviewMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static com.evops.constant.MuseumConstants.*;

/**
 * 修复材料批号与用量核算（museum-3）。
 *
 * <ul>
 *   <li>颜料 / 黏合剂 / 保护剂按批号入库，批号全库唯一；</li>
 *   <li>修复工单（项目须在修复中）领用，记录用量、使用部位、操作者，
 *       领用行快照材料名称与批号，此后批号变更不影响历史工单；</li>
 *   <li>过期 / 库存不足的批次不能领用；</li>
 *   <li>退料必须关联原领用单，一单一退、不超净领用量，同事务回补库存；</li>
 *   <li>登记实际用量后按「计划 vs 实际累计」核算，差异超阈值触发复核，
 *       定时任务可在重启后兜底重扫而不重复写复核记录。</li>
 * </ul>
 */
@Service
public class MaterialService {

    private static final List<String> CATEGORIES =
            Arrays.asList(MATERIAL_PIGMENT, MATERIAL_ADHESIVE, MATERIAL_PROTECTANT);
    private static final List<String> REVIEW_RESULTS =
            Arrays.asList(REVIEW_CONFIRMED, REVIEW_ADJUSTED);

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 计划 vs 实际用量差异复核默认阈值（百分比），可由配置覆盖 */
    @Value("${museum.material.review-threshold-percent:10.00}")
    private BigDecimal defaultThresholdPercent;

    private final MaterialBatchMapper batchMapper;
    private final MaterialPlanMapper planMapper;
    private final MaterialIssueMapper issueMapper;
    private final MaterialReturnMapper returnMapper;
    private final MaterialReviewMapper reviewMapper;
    private final RepairService repairService;
    private final LedgerService ledgerService;

    public MaterialService(MaterialBatchMapper batchMapper,
                           MaterialPlanMapper planMapper,
                           MaterialIssueMapper issueMapper,
                           MaterialReturnMapper returnMapper,
                           MaterialReviewMapper reviewMapper,
                           RepairService repairService,
                           LedgerService ledgerService) {
        this.batchMapper = batchMapper;
        this.planMapper = planMapper;
        this.issueMapper = issueMapper;
        this.returnMapper = returnMapper;
        this.reviewMapper = reviewMapper;
        this.repairService = repairService;
        this.ledgerService = ledgerService;
    }

    // ------------------------------------------------------------------
    // 入库
    // ------------------------------------------------------------------

    /** 材料按批号入库：批号唯一；入库时已过到期日的批次直接置为过期、不可领用 */
    @Transactional
    public MaterialBatch inbound(MaterialInboundRequest req) {
        String category = req.getCategory() == null ? null : req.getCategory().trim().toUpperCase();
        if (!CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("材料类别只能为 PIGMENT/ADHESIVE/PROTECTANT");
        }
        if (batchMapper.selectCount(new LambdaQueryWrapper<MaterialBatch>()
                .eq(MaterialBatch::getBatchNo, req.getBatchNo())) > 0) {
            throw new IllegalArgumentException("材料批号已存在: " + req.getBatchNo());
        }
        LocalDateTime now = LocalDateTime.now();
        MaterialBatch batch = new MaterialBatch();
        batch.setBatchNo(req.getBatchNo());
        batch.setMaterialName(req.getMaterialName());
        batch.setCategory(category);
        batch.setSpecification(req.getSpecification());
        batch.setManufacturer(req.getManufacturer());
        batch.setUnit(req.getUnit());
        batch.setInboundQty(req.getInboundQty());
        batch.setAvailableQty(req.getInboundQty());
        batch.setProductionDate(req.getProductionDate());
        batch.setInboundAt(now);
        batch.setExpireDate(req.getExpireDate());
        batch.setStatus(req.getExpireDate().isBefore(LocalDate.now()) ? BATCH_EXPIRED : BATCH_ACTIVE);
        batchMapper.insert(batch);

        ledgerService.append(MATERIAL_GLOBAL_SCOPE, null, null,
                ACTION_MATERIAL_INBOUND, batch.getBatchNo(),
                categoryName(category) + "入库，批号：" + batch.getBatchNo()
                        + "，名称：" + batch.getMaterialName()
                        + "，数量：" + batch.getInboundQty() + batch.getUnit()
                        + "，到期日：" + batch.getExpireDate(),
                now);
        return batch;
    }

    // ------------------------------------------------------------------
    // 工单材料计划
    // ------------------------------------------------------------------

    @Transactional
    public MaterialPlan createPlan(MaterialPlanRequest req) {
        RepairProject project = requireOpenProject(req.getProjectId());
        MaterialBatch batch = requireBatch(req.getBatchId());
        requireUsableBatch(batch);
        if (planMapper.selectCount(new LambdaQueryWrapper<MaterialPlan>()
                .eq(MaterialPlan::getProjectId, project.getId())
                .eq(MaterialPlan::getBatchId, batch.getId())) > 0) {
            throw new IllegalArgumentException("该项目已对批号 " + batch.getBatchNo()
                    + " 做过材料计划，同一项目同一批号只允许一条计划");
        }
        MaterialPlan plan = new MaterialPlan();
        plan.setProjectId(project.getId());
        plan.setBatchId(batch.getId());
        plan.setPlannedQty(req.getPlannedQty());
        plan.setUnit(batch.getUnit());
        plan.setReviewThresholdPercent(req.getReviewThresholdPercent());
        planMapper.insert(plan);
        return plan;
    }

    // ------------------------------------------------------------------
    // 领用
    // ------------------------------------------------------------------

    /**
     * 修复工单领用材料：项目须在修复中；批次须在有效期内且库存充足。
     * 同事务条件扣减库存，保证库存与修复进度一致；领用单记录用量/部位/操作者与批号快照。
     */
    @Transactional
    public MaterialIssue issue(MaterialIssueRequest req) {
        RepairProject project = repairService.requireProject(req.getProjectId());
        if (!PROJECT_REPAIRING.equals(project.getStatus())) {
            throw new IllegalArgumentException("项目不在修复中，不能领用材料: " + project.getProjectNo());
        }
        MaterialBatch batch = requireBatch(req.getBatchId());
        requireUsableBatch(batch);

        MaterialPlan plan = null;
        if (req.getPlanId() != null) {
            plan = requirePlan(req.getPlanId());
            if (!plan.getProjectId().equals(project.getId()) || !plan.getBatchId().equals(batch.getId())) {
                throw new IllegalArgumentException("材料计划与领用项目/批号不一致");
            }
        }

        BigDecimal qty = req.getIssueQty();
        // 条件扣减：库存足够才出库，并在扣到 0 时置为耗尽，杜绝并发超卖
        int updated = batchMapper.update(null, new LambdaUpdateWrapper<MaterialBatch>()
                .eq(MaterialBatch::getId, batch.getId())
                .eq(MaterialBatch::getStatus, BATCH_ACTIVE)
                .ge(MaterialBatch::getAvailableQty, qty)
                .setSql("available_qty = available_qty - " + qty.toPlainString())
                .setSql("status = CASE WHEN available_qty - " + qty.toPlainString()
                        + " <= 0 THEN '" + BATCH_EXHAUSTED + "' ELSE status END"));
        if (updated == 0) {
            throw new IllegalArgumentException("材料批号 " + batch.getBatchNo() + " 可用库存不足或已不可用");
        }

        LocalDateTime now = LocalDateTime.now();
        MaterialIssue issue = new MaterialIssue();
        issue.setIssueNo(generateNo("IL"));
        issue.setProjectId(project.getId());
        issue.setBatchId(batch.getId());
        issue.setPlanId(plan == null ? null : plan.getId());
        issue.setIssueQty(qty);
        issue.setReturnedQty(BigDecimal.ZERO);
        issue.setUnit(batch.getUnit());
        issue.setApplyPart(req.getApplyPart());
        issue.setOperator(req.getOperator());
        // 不可变快照：之后材料批号变更不影响历史工单
        issue.setMaterialName(batch.getMaterialName());
        issue.setBatchNo(batch.getBatchNo());
        issue.setIssuedAt(now);
        issue.setStatus(ISSUE_ISSUED);
        issueMapper.insert(issue);

        ledgerService.append(project.getRelicId(), project.getId(), null,
                ACTION_MATERIAL_ISSUE, issue.getIssueNo(),
                "领用" + categoryName(batch.getCategory()) + "【" + issue.getMaterialName()
                        + "】批号 " + issue.getBatchNo()
                        + "，用量 " + qty + issue.getUnit()
                        + "，部位：" + issue.getApplyPart()
                        + "，操作者：" + issue.getOperator(),
                now);
        return issue;
    }

    // ------------------------------------------------------------------
    // 登记实际用量（触发计划 vs 实际差异核算）
    // ------------------------------------------------------------------

    @Transactional
    public MaterialIssue reportUsage(Long issueId, BigDecimal actual) {
        MaterialIssue issue = requireIssue(issueId);
        if (issue.getActualQty() != null) {
            throw new IllegalArgumentException("领用单 " + issue.getIssueNo()
                    + " 已登记实际用量，不能重复登记");
        }
        if (actual == null || actual.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("实际用量必须大于 0");
        }
        if (actual.compareTo(issue.getIssueQty()) > 0) {
            throw new IllegalArgumentException("实际用量不能超过领用量 "
                    + issue.getIssueQty() + issue.getUnit());
        }
        // 净余量 = 领用量 - 已退料；实际用量不能占用已退料部分
        BigDecimal netRemain = issue.getIssueQty().subtract(issue.getReturnedQty());
        if (actual.compareTo(netRemain) > 0) {
            throw new IllegalArgumentException("实际用量不能超过扣减退料后的净领用量 "
                    + netRemain.toPlainString() + issue.getUnit());
        }

        LocalDateTime now = LocalDateTime.now();
        issue.setActualQty(actual);
        issue.setUsedAt(now);
        issueMapper.updateById(issue);

        RepairProject project = repairService.requireProject(issue.getProjectId());
        ledgerService.append(project.getRelicId(), project.getId(), null,
                ACTION_MATERIAL_USED, issue.getIssueNo(),
                "登记实际用量：" + issue.getMaterialName() + "（批号 " + issue.getBatchNo()
                        + "）" + actual + issue.getUnit()
                        + "，部位：" + issue.getApplyPart()
                        + "，操作者：" + issue.getOperator(),
                now);

        // 即时核算：计划下累计实际用量与计划偏差超阈值则触发复核（唯一约束兜底防重）
        if (issue.getPlanId() != null) {
            tryCreateDiffReview(requirePlan(issue.getPlanId()));
        }
        return issue;
    }

    // ------------------------------------------------------------------
    // 退料（必须关联原领用单）
    // ------------------------------------------------------------------

    @Transactional
    public MaterialReturn returnMaterial(MaterialReturnRequest req) {
        MaterialIssue issue = requireIssue(req.getIssueId());
        if (returnMapper.selectCount(new LambdaQueryWrapper<MaterialReturn>()
                .eq(MaterialReturn::getIssueId, issue.getId())) > 0) {
            throw new IllegalArgumentException("领用单 " + issue.getIssueNo()
                    + " 已办理过退料，一张领用单只能退料一次");
        }
        // 净可退 = 领用量 - 已退料 - 已实际使用
        BigDecimal used = issue.getActualQty() == null ? BigDecimal.ZERO : issue.getActualQty();
        BigDecimal maxReturn = issue.getIssueQty().subtract(issue.getReturnedQty()).subtract(used);
        if (req.getReturnQty().compareTo(maxReturn) > 0) {
            throw new IllegalArgumentException("退料量超过可退余量 "
                    + maxReturn.toPlainString() + issue.getUnit()
                    + "（领用量 " + issue.getIssueQty().toPlainString()
                    + "，已用 " + used.toPlainString() + "）");
        }

        LocalDateTime now = LocalDateTime.now();
        MaterialReturn ret = new MaterialReturn();
        ret.setReturnNo(generateNo("RT"));
        ret.setIssueId(issue.getId());
        ret.setProjectId(issue.getProjectId());
        ret.setBatchId(issue.getBatchId());
        ret.setReturnQty(req.getReturnQty());
        ret.setUnit(issue.getUnit());
        ret.setOperator(req.getOperator());
        ret.setReason(req.getReason());
        ret.setReturnedAt(now);
        returnMapper.insert(ret);

        // 回补原批号库存：耗尽批次恢复在库可用；已过期批次即使退回仍不可领用，保持 EXPIRED
        int updated = batchMapper.update(null, new LambdaUpdateWrapper<MaterialBatch>()
                .eq(MaterialBatch::getId, issue.getBatchId())
                .setSql("available_qty = available_qty + " + req.getReturnQty().toPlainString())
                .setSql("status = CASE WHEN status = '" + BATCH_EXPIRED
                        + "' THEN '" + BATCH_EXPIRED + "' ELSE '" + BATCH_ACTIVE + "' END"));
        if (updated == 0) {
            throw new IllegalStateException("退料回补库存失败，原批号不存在: id=" + issue.getBatchId());
        }

        issue.setReturnedQty(issue.getReturnedQty().add(req.getReturnQty()));
        issue.setStatus(ISSUE_RETURNED);
        issueMapper.updateById(issue);

        RepairProject project = repairService.requireProject(issue.getProjectId());
        ledgerService.append(project.getRelicId(), project.getId(), null,
                ACTION_MATERIAL_RETURN, ret.getReturnNo(),
                "退料关联原领用单 " + issue.getIssueNo()
                        + "，材料：" + issue.getMaterialName() + "（批号 " + issue.getBatchNo()
                        + "），退料 " + req.getReturnQty() + issue.getUnit()
                        + "，操作者：" + req.getOperator()
                        + (req.getReason() == null || req.getReason().isEmpty()
                                ? "" : "，原因：" + req.getReason()),
                now);
        return ret;
    }

    // ------------------------------------------------------------------
    // 用量差异复核
    // ------------------------------------------------------------------

    /**
     * 核算单个材料计划：汇总其下所有已登记实际用量，与计划用量比较；
     * 差异百分比严格超过阈值、且尚无复核记录时，新建一条复核任务。
     * 已存在复核（无论是否已处理）一律不重复写。
     *
     * <p>本方法另由定时任务跨 Bean 调用，每次调用独立事务；
     * 并发 / 重启重扫撞上 (plan_id, reason) 唯一约束时由调用方捕获跳过。</p>
     *
     * @return 新建的复核任务；未超阈值或已存在时返回 null
     */
    @Transactional
    public MaterialReview tryCreateDiffReview(MaterialPlan plan) {
        List<MaterialIssue> usedIssues = issueMapper.selectList(new LambdaQueryWrapper<MaterialIssue>()
                .eq(MaterialIssue::getPlanId, plan.getId())
                .isNotNull(MaterialIssue::getActualQty));
        if (usedIssues.isEmpty()) {
            return null;
        }
        BigDecimal actualTotal = usedIssues.stream()
                .map(MaterialIssue::getActualQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal diffPercent = actualTotal.subtract(plan.getPlannedQty()).abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(plan.getPlannedQty(), 2, RoundingMode.HALF_UP);
        BigDecimal threshold = plan.getReviewThresholdPercent() == null
                ? defaultThresholdPercent : plan.getReviewThresholdPercent();
        if (diffPercent.compareTo(threshold) <= 0) {
            return null;
        }
        if (reviewMapper.selectCount(new LambdaQueryWrapper<MaterialReview>()
                .eq(MaterialReview::getPlanId, plan.getId())
                .eq(MaterialReview::getReason, REVIEW_DAT_DIFF)) > 0) {
            return null;
        }

        // 代表领用单：最近一次登记实际用量的单子，操作者/部位取它，保证不丢操作者
        MaterialIssue representative = usedIssues.stream()
                .max(Comparator.comparing(MaterialIssue::getUsedAt)
                        .thenComparing(MaterialIssue::getId))
                .orElseThrow(IllegalStateException::new);
        if (representative.getOperator() == null || representative.getOperator().isEmpty()) {
            // 操作者为领用必填，理论不可达，兜底防止产生无操作者的复核记录
            throw new IllegalStateException("领用单缺少操作者，不能生成复核任务: "
                    + representative.getIssueNo());
        }

        LocalDateTime now = LocalDateTime.now();
        MaterialReview review = new MaterialReview();
        review.setReviewNo(generateNo("RV"));
        review.setPlanId(plan.getId());
        review.setProjectId(plan.getProjectId());
        review.setIssueId(representative.getId());
        review.setBatchId(plan.getBatchId());
        review.setReason(REVIEW_DAT_DIFF);
        review.setPlannedQty(plan.getPlannedQty());
        review.setActualQty(actualTotal);
        review.setDiffPercent(diffPercent);
        review.setThresholdPercent(threshold);
        review.setApplyPart(representative.getApplyPart());
        review.setIssueOperator(representative.getOperator());
        review.setStatus(REVIEW_PENDING);
        review.setDetectedAt(now);
        reviewMapper.insert(review);

        RepairProject project = repairService.requireProject(plan.getProjectId());
        ledgerService.append(project.getRelicId(), project.getId(), null,
                ACTION_MATERIAL_REVIEW, review.getReviewNo(),
                "材料用量差异超阈值触发复核：批号 " + representative.getBatchNo()
                        + "，计划 " + plan.getPlannedQty() + plan.getUnit()
                        + "，实际累计 " + actualTotal + plan.getUnit()
                        + "，偏差 " + diffPercent + "%（阈值 " + threshold + "%）"
                        + "，部位：" + representative.getApplyPart()
                        + "，领用操作者：" + representative.getOperator(),
                now);
        return review;
    }

    /** 复核处理：CONFIRMED 核实无误 / ADJUSTED 已调整 */
    @Transactional
    public MaterialReview handleReview(Long reviewId, String result, String handledBy, String opinion) {
        MaterialReview review = requireReview(reviewId);
        if (!REVIEW_PENDING.equals(review.getStatus())) {
            throw new IllegalArgumentException("复核任务已处理，不能重复处理: " + review.getReviewNo());
        }
        String r = result == null ? null : result.trim().toUpperCase();
        if (!REVIEW_RESULTS.contains(r)) {
            throw new IllegalArgumentException("复核结论只能为 CONFIRMED/ADJUSTED");
        }
        LocalDateTime now = LocalDateTime.now();
        review.setStatus(r);
        review.setHandledBy(handledBy);
        review.setHandledAt(now);
        review.setHandleOpinion(opinion);
        reviewMapper.updateById(review);

        RepairProject project = repairService.requireProject(review.getProjectId());
        ledgerService.append(project.getRelicId(), project.getId(), null,
                ACTION_MATERIAL_REVIEW_HANDLE, review.getReviewNo(),
                "用量差异复核处理：" + (REVIEW_CONFIRMED.equals(r) ? "核实无误" : "已调整")
                        + "，复核人：" + handledBy
                        + "，原领用操作者：" + review.getIssueOperator()
                        + (opinion == null || opinion.isEmpty() ? "" : "，意见：" + opinion),
                now);
        return review;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public MaterialBatch requireBatch(Long batchId) {
        MaterialBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("材料批号不存在: id=" + batchId);
        }
        return batch;
    }

    public MaterialIssue requireIssue(Long issueId) {
        MaterialIssue issue = issueMapper.selectById(issueId);
        if (issue == null) {
            throw new IllegalArgumentException("材料领用单不存在: id=" + issueId);
        }
        return issue;
    }

    public MaterialPlan requirePlan(Long planId) {
        MaterialPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new IllegalArgumentException("材料计划不存在: id=" + planId);
        }
        return plan;
    }

    public MaterialReview requireReview(Long reviewId) {
        MaterialReview review = reviewMapper.selectById(reviewId);
        if (review == null) {
            throw new IllegalArgumentException("复核任务不存在: id=" + reviewId);
        }
        return review;
    }

    public List<MaterialBatch> listBatches(String category, String status) {
        LambdaQueryWrapper<MaterialBatch> wrapper = new LambdaQueryWrapper<MaterialBatch>()
                .orderByDesc(MaterialBatch::getId);
        if (category != null && !category.isEmpty()) {
            wrapper.eq(MaterialBatch::getCategory, category.trim().toUpperCase());
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(MaterialBatch::getStatus, status.trim().toUpperCase());
        }
        return batchMapper.selectList(wrapper);
    }

    public List<MaterialPlan> listPlansByProject(Long projectId) {
        return planMapper.selectList(new LambdaQueryWrapper<MaterialPlan>()
                .eq(MaterialPlan::getProjectId, projectId)
                .orderByAsc(MaterialPlan::getId));
    }

    /** 全部材料计划（定时复核扫描用） */
    public List<MaterialPlan> listAllPlans() {
        return planMapper.selectList(new LambdaQueryWrapper<MaterialPlan>()
                .orderByAsc(MaterialPlan::getId));
    }

    public List<MaterialIssue> listIssuesByProject(Long projectId) {
        return issueMapper.selectList(new LambdaQueryWrapper<MaterialIssue>()
                .eq(MaterialIssue::getProjectId, projectId)
                .orderByAsc(MaterialIssue::getId));
    }

    public List<MaterialReturn> listReturnsByProject(Long projectId) {
        return returnMapper.selectList(new LambdaQueryWrapper<MaterialReturn>()
                .eq(MaterialReturn::getProjectId, projectId)
                .orderByAsc(MaterialReturn::getId));
    }

    public MaterialReturn getReturnByIssue(Long issueId) {
        return returnMapper.selectOne(new LambdaQueryWrapper<MaterialReturn>()
                .eq(MaterialReturn::getIssueId, issueId));
    }

    public List<MaterialReview> listReviews(String status) {
        LambdaQueryWrapper<MaterialReview> wrapper = new LambdaQueryWrapper<MaterialReview>()
                .orderByDesc(MaterialReview::getId);
        if (status != null && !status.isEmpty()) {
            wrapper.eq(MaterialReview::getStatus, status.trim().toUpperCase());
        }
        return reviewMapper.selectList(wrapper);
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private RepairProject requireOpenProject(Long projectId) {
        RepairProject project = repairService.requireProject(projectId);
        if (PROJECT_ACCEPTED.equals(project.getStatus()) || PROJECT_CANCELLED.equals(project.getStatus())) {
            throw new IllegalArgumentException("项目已"
                    + (PROJECT_ACCEPTED.equals(project.getStatus()) ? "验收闭环" : "作废")
                    + "，不能再做材料计划: " + project.getProjectNo());
        }
        return project;
    }

    /** 校验批次可领用：存在、在库、未过期；发现到期未刷新状态的顺带置为 EXPIRED */
    private void requireUsableBatch(MaterialBatch batch) {
        if (BATCH_EXPIRED.equals(batch.getStatus())
                || batch.getExpireDate().isBefore(LocalDate.now())) {
            if (!BATCH_EXPIRED.equals(batch.getStatus())) {
                batch.setStatus(BATCH_EXPIRED);
                batchMapper.updateById(batch);
            }
            throw new IllegalArgumentException("材料批号 " + batch.getBatchNo()
                    + " 已于 " + batch.getExpireDate() + " 到期，过期材料不能领用");
        }
        if (BATCH_EXHAUSTED.equals(batch.getStatus())
                || batch.getAvailableQty().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("材料批号 " + batch.getBatchNo() + " 库存已耗尽，不能领用");
        }
    }

    private String categoryName(String category) {
        if (MATERIAL_PIGMENT.equals(category)) {
            return "颜料";
        }
        if (MATERIAL_ADHESIVE.equals(category)) {
            return "黏合剂";
        }
        if (MATERIAL_PROTECTANT.equals(category)) {
            return "保护剂";
        }
        return category;
    }

    private String generateNo(String prefix) {
        int rnd = java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000);
        return prefix + LocalDateTime.now().format(NO_FMT) + "-" + String.format("%06d", rnd);
    }
}
