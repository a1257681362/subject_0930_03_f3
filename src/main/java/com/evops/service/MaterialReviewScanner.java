package com.evops.service;

import com.evops.entity.MaterialPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 修复材料「计划用量 vs 实际用量」差异复核定时扫描。
 *
 * <p>独立编排 Bean：逐条调用 {@link MaterialService#tryCreateDiffReview}（跨 Bean
 * 走事务代理，每条计划独立事务），单条失败 / 撞唯一约束不影响其余。</p>
 *
 * <p>重启幂等：复核记录按 (plan_id, reason) 数据库唯一，应用重启后定时任务再次扫描，
 * 已生成复核的计划不会再产生第二条（不多写记录）；复核记录始终冗余领用操作者，
 * 重新组装也不丢操作者。</p>
 */
@Component
public class MaterialReviewScanner {

    private static final Logger log = LoggerFactory.getLogger(MaterialReviewScanner.class);

    private final MaterialService materialService;

    public MaterialReviewScanner(MaterialService materialService) {
        this.materialService = materialService;
    }

    /**
     * 定时扫描：默认每天 08:00 一次，cron 可由 museum.material.review-scan-cron 覆盖。
     * 重启后首轮即会执行；靠唯一约束保证不重复写。
     */
    @Scheduled(cron = "${museum.material.review-scan-cron:0 0 8 * * ?}")
    public void scheduledScan() {
        int created = scan();
        if (created > 0) {
            log.info("修复材料用量差异定时扫描完成，新增复核记录 {} 条", created);
        }
    }

    /**
     * 扫描全部材料计划，对差异超阈值且尚无复核记录者生成复核任务。
     *
     * @return 本次实际新建的复核记录条数
     */
    public int scan() {
        List<MaterialPlan> plans = materialService.listAllPlans();
        int created = 0;
        for (MaterialPlan plan : plans) {
            try {
                if (materialService.tryCreateDiffReview(plan) != null) {
                    created++;
                }
            } catch (DuplicateKeyException dup) {
                // 重启重扫 / 并发下别的执行器已写入同 (plan_id, reason)：不多写，跳过
                log.info("复核记录已存在，跳过 planId={}", plan.getId());
            } catch (Exception ex) {
                // 单条核算失败不拖垮整轮扫描与调度线程
                log.error("材料用量复核扫描异常 planId={}", plan.getId(), ex);
            }
        }
        return created;
    }
}
