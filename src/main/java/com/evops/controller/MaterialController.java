package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.MaterialInboundRequest;
import com.evops.dto.MaterialIssueRequest;
import com.evops.dto.MaterialPlanRequest;
import com.evops.dto.MaterialReturnRequest;
import com.evops.dto.MaterialReviewHandleRequest;
import com.evops.dto.MaterialUsageRequest;
import com.evops.entity.MaterialBatch;
import com.evops.entity.MaterialIssue;
import com.evops.entity.MaterialPlan;
import com.evops.entity.MaterialReturn;
import com.evops.entity.MaterialReview;
import com.evops.service.MaterialReviewScanner;
import com.evops.service.MaterialService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 修复材料批号与用量核算（museum-3）。
 * 入库、库存、工单材料计划、领用（用量/部位/操作者）、退料（关联原单）、
 * 实际用量登记与用量差异复核（定时扫描 + 手动触发）。
 */
@RestController
public class MaterialController {

    private final MaterialService materialService;
    private final MaterialReviewScanner reviewScanner;

    public MaterialController(MaterialService materialService,
                              MaterialReviewScanner reviewScanner) {
        this.materialService = materialService;
        this.reviewScanner = reviewScanner;
    }

    // ---------------- 批号库存 ----------------

    /** 颜料 / 黏合剂 / 保护剂按批号入库 */
    @PostMapping("/api/materials/inbound")
    public ApiResponse<MaterialBatch> inbound(@Valid @RequestBody MaterialInboundRequest request) {
        return ApiResponse.ok(materialService.inbound(request));
    }

    /** 批号库存列表，可按类别 / 状态过滤 */
    @GetMapping("/api/materials/batches")
    public ApiResponse<List<MaterialBatch>> listBatches(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(materialService.listBatches(category, status));
    }

    @GetMapping("/api/materials/batches/{id}")
    public ApiResponse<MaterialBatch> getBatch(@PathVariable Long id) {
        return ApiResponse.ok(materialService.requireBatch(id));
    }

    // ---------------- 工单材料计划 ----------------

    @PostMapping("/api/repair-projects/{projectId}/material-plans")
    public ApiResponse<MaterialPlan> createPlan(@PathVariable Long projectId,
                                                @Valid @RequestBody MaterialPlanRequest request) {
        request.setProjectId(projectId);
        return ApiResponse.ok(materialService.createPlan(request));
    }

    @GetMapping("/api/repair-projects/{projectId}/material-plans")
    public ApiResponse<List<MaterialPlan>> listPlans(@PathVariable Long projectId) {
        return ApiResponse.ok(materialService.listPlansByProject(projectId));
    }

    // ---------------- 领用 / 实际用量 / 退料 ----------------

    /** 工单领用材料：记录用量、部位、操作者；过期批次 / 库存不足会被拒绝 */
    @PostMapping("/api/repair-projects/{projectId}/material-issues")
    public ApiResponse<MaterialIssue> issue(@PathVariable Long projectId,
                                            @Valid @RequestBody MaterialIssueRequest request) {
        request.setProjectId(projectId);
        return ApiResponse.ok(materialService.issue(request));
    }

    @GetMapping("/api/repair-projects/{projectId}/material-issues")
    public ApiResponse<List<MaterialIssue>> listIssues(@PathVariable Long projectId) {
        return ApiResponse.ok(materialService.listIssuesByProject(projectId));
    }

    /** 领用单详情：领用 + 关联退料单（退料必关联原领用单） */
    @GetMapping("/api/material-issues/{id}")
    public ApiResponse<Map<String, Object>> getIssue(@PathVariable Long id) {
        MaterialIssue issue = materialService.requireIssue(id);
        MaterialReturn materialReturn = materialService.getReturnByIssue(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("issue", issue);
        detail.put("materialReturn", materialReturn);
        return ApiResponse.ok(detail);
    }

    /** 登记实际用量，触发计划 vs 实际差异核算（超阈值即生成复核任务） */
    @PostMapping("/api/material-issues/{id}/usage")
    public ApiResponse<MaterialIssue> reportUsage(@PathVariable Long id,
                                                  @Valid @RequestBody MaterialUsageRequest request) {
        return ApiResponse.ok(materialService.reportUsage(id, request.getActualQty()));
    }

    /** 退料：必须关联原领用单，一单一退、不超净领用量，同事务回补库存 */
    @PostMapping("/api/material-returns")
    public ApiResponse<MaterialReturn> returnMaterial(
            @Valid @RequestBody MaterialReturnRequest request) {
        return ApiResponse.ok(materialService.returnMaterial(request));
    }

    @GetMapping("/api/repair-projects/{projectId}/material-returns")
    public ApiResponse<List<MaterialReturn>> listReturns(@PathVariable Long projectId) {
        return ApiResponse.ok(materialService.listReturnsByProject(projectId));
    }

    // ---------------- 用量差异复核 ----------------

    /** 复核任务列表，可按状态过滤（PENDING/CONFIRMED/ADJUSTED） */
    @GetMapping("/api/material-reviews")
    public ApiResponse<List<MaterialReview>> listReviews(
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(materialService.listReviews(status));
    }

    /** 处理复核任务：CONFIRMED 核实无误 / ADJUSTED 已调整 */
    @PostMapping("/api/material-reviews/{id}/handle")
    public ApiResponse<MaterialReview> handleReview(@PathVariable Long id,
                                                    @Valid @RequestBody MaterialReviewHandleRequest request) {
        return ApiResponse.ok(materialService.handleReview(
                id, request.getResult(), request.getHandledBy(), request.getOpinion()));
    }

    /** 手动触发一次用量差异扫描（与定时任务同一套幂等逻辑，重启后重扫不多写） */
    @PostMapping("/api/material-reviews/scan")
    public ApiResponse<Map<String, Object>> scan() {
        int created = reviewScanner.scan();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("message", created > 0
                ? "扫描完成，新增复核记录 " + created + " 条"
                : "扫描完成，无新增复核记录（已存在的不重复生成）");
        return ApiResponse.ok(result);
    }
}
