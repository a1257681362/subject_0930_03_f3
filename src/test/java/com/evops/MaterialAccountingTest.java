package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.mapper.MaterialBatchMapper;
import com.evops.mapper.MaterialIssueMapper;
import com.evops.mapper.MaterialReviewMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 修复材料批号与用量核算（museum-3）验收：
 * 颜料/黏合剂/保护剂按批号入库；领用记录用量/部位/操作者；过期不能领用；
 * 退料关联原领用单；计划与实际差异超阈值触发复核；批号变更不影响历史工单；
 * 库存与修复进度一致；定时任务重启重扫不多写复核记录、不丢操作者。
 *
 * <p>使用独立内存库，不调用起步接口。</p>
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:material-accounting;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MaterialAccountingTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MaterialBatchMapper batchMapper;
    @Autowired
    private MaterialIssueMapper issueMapper;
    @Autowired
    private MaterialReviewMapper reviewMapper;

    @Test
    void material_batch_and_usage_accounting_rules_hold() throws Exception {
        // ---- 前置：建档 -> 入藏 -> 立项 -> 开工（修复中才能领用材料） ----
        long relicId = createRelic("{"
                + "\"relicNo\":\"M-001\",\"name\":\"材料核算测试铜尊\",\"category\":\"青铜器\","
                + "\"material\":\"青铜\",\"era\":\"西周\",\"source\":\"考古发掘\","
                + "\"preservationGrade\":\"ONE\"}");
        expectOk(postJson("/api/relics/" + relicId + "/intake", "{"
                + "\"intakeNo\":\"MI-001\",\"location\":\"三号库·铜器区·C-01\","
                + "\"photos\":[{\"photoUrl\":\"photos/m001/a.jpg\"}]}"));

        long projectId = dataOf(postJson("/api/repair-projects", "{"
                + "\"projectNo\":\"MP-001\",\"relicId\":" + relicId
                + ",\"client\":\"藏品部\",\"plan\":\"清洗-补配-封护\"}")).get("id").asLong();

        // 未开工（PLANNED）不能领用材料
        long pigmentId = inboundPigment(); // 先入库以便构造领用请求
        expectFail(postJson("/api/repair-projects/" + projectId + "/material-issues", "{"
                + "\"batchId\":" + pigmentId + ",\"issueQty\":10,\"unit\":\"g\","
                + "\"applyPart\":\"x\",\"operator\":\"修复师甲\"}"), "不能领用材料");

        expectOk(postEmpty("/api/repair-projects/" + projectId + "/start"));

        // ---- 1. 三类材料按批号入库 ----
        long adhesiveId = inbound("{"
                + "\"batchNo\":\"ADH-B\",\"materialName\":\"B72 黏合剂\",\"category\":\"ADHESIVE\","
                + "\"unit\":\"ml\",\"inboundQty\":200,\"expireDate\":\"2099-12-31\"}");
        long protectantExpiredId = inbound("{"
                + "\"batchNo\":\"PRT-C\",\"materialName\":\"丙烯酸封护剂\",\"category\":\"PROTECTANT\","
                + "\"unit\":\"g\",\"inboundQty\":100,\"expireDate\":\"2020-01-01\"}");

        // 批号全库唯一：重复入库被拒
        expectFail(postJson("/api/materials/inbound", "{"
                + "\"batchNo\":\"PIG-A\",\"materialName\":\"重复批号\",\"category\":\"PIGMENT\","
                + "\"unit\":\"g\",\"inboundQty\":1,\"expireDate\":\"2099-12-31\"}"), "材料批号已存在");
        // 类别非法
        expectFail(postJson("/api/materials/inbound", "{"
                + "\"batchNo\":\"BAD-1\",\"materialName\":\"x\",\"category\":\"OTHER\","
                + "\"unit\":\"g\",\"inboundQty\":1,\"expireDate\":\"2099-12-31\"}"), "材料类别");

        // 入库即到期的批次状态为 EXPIRED
        assertEquals("EXPIRED", batchMapper.selectById(protectantExpiredId).getStatus());

        // ---- 2. 过期材料不能领用 ----
        expectFail(postJson("/api/repair-projects/" + projectId + "/material-issues", "{"
                + "\"batchId\":" + protectantExpiredId + ",\"issueQty\":10,"
                + "\"applyPart\":\"表面\",\"operator\":\"修复师甲\"}"), "过期材料不能领用");

        // ---- 3. 工单材料计划 + 领用（记录用量、部位、操作者） ----
        long planPigmentId = dataOf(postJson(
                "/api/repair-projects/" + projectId + "/material-plans", "{"
                        + "\"batchId\":" + pigmentId + ",\"plannedQty\":100}")).get("id").asLong();

        // 同项目同批号只允许一条计划
        expectFail(postJson("/api/repair-projects/" + projectId + "/material-plans", "{"
                + "\"batchId\":" + pigmentId + ",\"plannedQty\":50}"), "只允许一条计划");

        long issue1 = issue(projectId, pigmentId, planPigmentId, "100", "腹部裂隙", "修复师甲");
        assertAvailable(pigmentId, "400.000"); // 500 - 100

        long issue2 = issue(projectId, pigmentId, planPigmentId, "30", "口沿残损处", "修复师乙");
        assertAvailable(pigmentId, "370.000"); // 400 - 30

        // 库存不足不能领用，且库存保持不变（事务回滚 / 条件扣减）
        BigDecimal before = batchMapper.selectById(adhesiveId).getAvailableQty();
        expectFail(postJson("/api/repair-projects/" + projectId + "/material-issues", "{"
                + "\"batchId\":" + adhesiveId + ",\"issueQty\":10000,"
                + "\"applyPart\":\"整体\",\"operator\":\"修复师甲\"}"), "库存不足");
        assertEquals(before, batchMapper.selectById(adhesiveId).getAvailableQty());

        // ---- 4. 计划用量 vs 实际用量差异超阈值触发复核（默认阈值 10%，严格超过） ----
        // issue1 实际 100（累计 100，偏差 0%）不触发
        expectOk(postJson("/api/material-issues/" + issue1 + "/usage", "{\"actualQty\":100}"));
        assertEquals(0, reviewMapper.selectCount(null));
        // 重复登记实际用量被拒
        expectFail(postJson("/api/material-issues/" + issue1 + "/usage",
                "{\"actualQty\":90}"), "不能重复登记");

        // issue2 实际 20（累计 120 vs 计划 100，偏差 20% > 10%）即时触发 1 条复核
        expectOk(postJson("/api/material-issues/" + issue2 + "/usage", "{\"actualQty\":20}"));
        assertEquals(1, reviewMapper.selectCount(null));

        JsonNode review = dataOf(getJson("/api/material-reviews?status=PENDING"));
        assertEquals(1, review.size());
        long reviewId = review.get(0).get("id").asLong();
        assertEquals("DAT_DIFF", review.get(0).get("reason").asText());
        assertEquals(0, new BigDecimal(review.get(0).get("diffPercent").asText())
                .compareTo(new BigDecimal("20.00")));
        assertEquals(0, new BigDecimal(review.get(0).get("thresholdPercent").asText())
                .compareTo(new BigDecimal("10.00")));
        // 不丢操作者：复核记录携带领用操作者（最近登记的 issue2 = 修复师乙）
        assertEquals("修复师乙", review.get(0).get("issueOperator").asText());
        assertEquals("口沿残损处", review.get(0).get("applyPart").asText());

        // 实际用量不能超过领用量：领 60，报 70 被拒
        long adhesivePlan = dataOf(postJson(
                "/api/repair-projects/" + projectId + "/material-plans", "{"
                        + "\"batchId\":" + adhesiveId + ",\"plannedQty\":50}")).get("id").asLong();
        long issueAdh = issue(projectId, adhesiveId, adhesivePlan, "60", "裂隙粘接", "修复师甲");
        expectFail(postJson("/api/material-issues/" + issueAdh + "/usage",
                "{\"actualQty\":70}"), "实际用量不能超过领用量");

        // 实际 52 vs 计划 50，偏差正好 4%（未严格超过 10%）不触发复核
        expectOk(postJson("/api/material-issues/" + issueAdh + "/usage", "{\"actualQty\":52}"));
        assertEquals(1, reviewMapper.selectCount(null));

        // ---- 5. 定时任务重启后重扫：不多写记录、不丢操作者 ----
        // 手动触发与定时任务同一套幂等逻辑；连续两轮（模拟应用重启后再次扫描）
        assertEquals(0, dataOf(postEmpty("/api/material-reviews/scan")).get("created").asInt());
        assertEquals(0, dataOf(postEmpty("/api/material-reviews/scan")).get("created").asInt());
        assertEquals(1, reviewMapper.selectCount(null));
        // 复核记录上的操作者仍然保留
        assertEquals("修复师乙",
                reviewMapper.selectById(reviewId).getIssueOperator());

        // 复核处理：CONFIRMED；处理后再扫描也不会重新生成
        expectOk(postJson("/api/material-reviews/" + reviewId + "/handle", "{"
                + "\"result\":\"CONFIRMED\",\"handledBy\":\"复核组\",\"opinion\":\"差异系补配随色追加用料，核实无误\"}"));
        assertEquals("CONFIRMED", reviewMapper.selectById(reviewId).getStatus());
        assertEquals(0, dataOf(postEmpty("/api/material-reviews/scan")).get("created").asInt());
        // 已处理不能重复处理
        expectFail(postJson("/api/material-reviews/" + reviewId + "/handle",
                "{\"result\":\"ADJUSTED\",\"handledBy\":\"复核组\"}"), "不能重复处理");

        // ---- 6. 退料必须关联原领用单 ----
        long issue3 = issue(projectId, pigmentId, null, "50", "预留补配", "修复师甲");
        assertAvailable(pigmentId, "320.000"); // 370 - 50

        // 退料关联不存在的领用单 -> 失败
        expectFail(postJson("/api/material-returns", "{"
                + "\"issueId\":999999,\"returnQty\":1,\"operator\":\"修复师甲\"}"), "领用单不存在");

        // 正常退料 30（关联原领用单 issue3），同事务回补库存
        JsonNode ret = dataOf(postJson("/api/material-returns", "{"
                + "\"issueId\":" + issue3 + ",\"returnQty\":30,\"operator\":\"库管员\","
                + "\"reason\":\"补配余量退回\"}"));
        assertEquals(issue3, ret.get("issueId").asLong());
        assertAvailable(pigmentId, "350.000"); // 320 + 30
        assertEquals("RETURNED", issueMapper.selectById(issue3).getStatus());

        // 领用单详情能回溯到关联退料单
        JsonNode issueDetail = dataOf(getJson("/api/material-issues/" + issue3));
        assertNotNull(issueDetail.get("materialReturn"));
        assertEquals(30, issueDetail.get("materialReturn").get("returnQty").asInt());

        // 一张领用单只能退一次
        expectFail(postJson("/api/material-returns", "{"
                + "\"issueId\":" + issue3 + ",\"returnQty\":5,\"operator\":\"库管员\"}"), "只能退料一次");

        // 退料不能超过净领用量：新领 20，试图退 30 -> 失败
        long issue4 = issue(projectId, pigmentId, null, "20", "局部试验", "修复师甲");
        expectFail(postJson("/api/material-returns", "{"
                + "\"issueId\":" + issue4 + ",\"returnQty\":30,\"operator\":\"库管员\"}"), "可退余量");

        // ---- 7. 材料批号变更不影响历史工单（快照） ----
        // 新入库一个同材料、不同批号的批次
        long pigment2Id = inbound("{"
                + "\"batchNo\":\"PIG-A2\",\"materialName\":\"矿物颜料\",\"category\":\"PIGMENT\","
                + "\"unit\":\"g\",\"inboundQty\":500,\"expireDate\":\"2099-12-31\"}");
        // 历史领用单仍记录领用当时的材料名称与批号，不随新批号变化
        JsonNode oldIssue = dataOf(getJson("/api/material-issues/" + issue1)).get("issue");
        assertEquals("矿物颜料", oldIssue.get("materialName").asText());
        assertEquals("PIG-A", oldIssue.get("batchNo").asText());
        assertTrue(oldIssue.get("batchId").asLong() != pigment2Id);

        // ---- 8. 库存与修复进度一致：项目闭环后不能再做材料计划 ----
        // 登记一个阶段以便完成修复
        expectOk(postJson("/api/repair-projects/" + projectId + "/stages", "{"
                + "\"stageName\":\"封护\",\"stageResult\":\"完成\",\"operator\":\"修复师甲\"}"));
        expectOk(postEmpty("/api/repair-projects/" + projectId + "/finish"));
        expectOk(postJson("/api/repair-projects/" + projectId + "/accept", "{"
                + "\"acceptedBy\":\"验收组\",\"result\":\"PASS\"}"));
        expectFail(postJson("/api/repair-projects/" + projectId + "/material-plans", "{"
                + "\"batchId\":" + pigment2Id + ",\"plannedQty\":10}"), "不能再做材料计划");

        // 项目详情聚合了材料计划/领用/退料，进度与材料台账可一起核对
        JsonNode detail = dataOf(getJson("/api/repair-projects/" + projectId));
        assertTrue(detail.get("materialPlans").size() >= 2);
        assertTrue(detail.get("materialIssues").size() >= 4);
        assertTrue(detail.get("materialReturns").size() >= 1);
    }

    // ------------------------------------------------------------------

    private long inboundPigment() throws Exception {
        return inbound("{"
                + "\"batchNo\":\"PIG-A\",\"materialName\":\"矿物颜料\",\"category\":\"PIGMENT\","
                + "\"unit\":\"g\",\"inboundQty\":500,\"expireDate\":\"2099-12-31\"}");
    }

    private long inbound(String body) throws Exception {
        return dataOf(postJson("/api/materials/inbound", body)).get("id").asLong();
    }

    private long issue(long projectId, long batchId, Long planId,
                       String qty, String part, String operator) throws Exception {
        String planField = planId == null ? "" : ",\"planId\":" + planId;
        return dataOf(postJson("/api/repair-projects/" + projectId + "/material-issues", "{"
                + "\"batchId\":" + batchId + planField
                + ",\"issueQty\":" + qty
                + ",\"applyPart\":\"" + part + "\",\"operator\":\"" + operator + "\"}"))
                .get("id").asLong();
    }

    private void assertAvailable(long batchId, String expected) {
        assertEquals(0, batchMapper.selectById(batchId).getAvailableQty()
                .compareTo(new BigDecimal(expected)));
    }

    private long createRelic(String body) throws Exception {
        return dataOf(postJson("/api/relics", body)).get("id").asLong();
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult postEmpty(String uri) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .get("data");
    }

    private void expectOk(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(root.get("success").asBoolean(),
                "期望成功，实际: " + root.get("message").asText());
    }

    private void expectFail(MvcResult result, String messageContains) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(false, root.get("success").asBoolean());
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际: " + root.get("message").asText());
    }
}
