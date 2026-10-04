# EVOPS 基础工作区

这是 springboot 的骨架项目。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

启动前准备：

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；Spring Boot 会自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。

`docs/schema/evops.sql` 是交付副本，与工作区 `src/main/resources/schema.sql` 保持一致（骨架原约定的
`../../docs/schema/evops.sql` 在当前环境根目录无写权限，故置于工作区内）。

## 博物馆文物修复台账（museum-1）

在骨架上新增的业务模块，**不含票务模块**。

### 数据模型

| 表 | 说明 |
| --- | --- |
| `t_relic` | 文物档案：文物编号、材质、年代、来源、保存等级（ONE/TWO/THREE）；撤销只置 `REVOKED` 状态 |
| `t_relic_intake` | 入藏登记：尺寸（高宽厚、重量）、库位、入藏时间，一件文物一条 |
| `t_relic_photo` | 入藏关联照片，一张照片一行 |
| `t_repair_project` | 修复项目：项目编号、委托人、修复方案；状态机见下 |
| `t_repair_stage` | 修复阶段结果，阶段号项目内递增 |
| `t_repair_acceptance` | 验收记录，一个项目一条 |
| `t_relic_loan` | 借出/归还单，一条记录贯通一次借还 |
| `t_relic_ledger` | 台账流水：入藏/借出/归还/修复/验收/撤销/作废/影像归档/权限调整全部追加留痕 |
| `t_bootstrap_lock` | 起步幂等锁，固定单行 id=1 |
| `t_photo_file` | 影像物理文件，SHA-256 内容寻址、全库唯一一份；作废不删文件 |
| `t_photo_batch` | 拍摄批次：一次拍摄的正面/背面/底部/细部照片归一批次 |
| `t_relic_image` | 文物影像版本引用：一张照片一行，跨修复阶段形成新版本 |
| `t_permission_policy` | 文物修复权限开关（单行 id=1），收紧时影像上传停止 |

约束：文物编号 `uk_relic_no`、项目编号 `uk_project_no` 数据库层唯一（含已撤销/已作废记录）；
撤销与作废均不删除任何数据。

### 状态链路

- 修复项目：`PLANNED → REPAIRING → REPAIRED → ACCEPTED`；`PLANNED/REPAIRING` 可 `CANCELLED`。
- 借出：`LENT → RETURNED`（在原单上更新，不新增记录）。
- 跨业务校验：文物借出未还不能重复借出、不能开工修复；有未闭环借还/项目的文物不能撤销。

### 主要接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/bootstrap` | **博物馆文物修复档案起步**：首次播种一条走完全链路的有效样本 |
| `GET /api/bootstrap` | 只读查看起步样本（未起步为 null） |
| `POST /api/relics` | 文物建档（编号重复返回失败） |
| `GET  /api/relics?status=&keyword=` | 档案列表 |
| `GET  /api/relics/{id}` | 完整档案：档案 + 入藏照片 + 借还 + 项目 + 台账流水 |
| `POST /api/relics/{id}/intake` | 入藏登记（照片/尺寸/库位，至少一张照片） |
| `POST /api/relics/{id}/revoke` | 撤销（只改状态，记录保留） |
| `POST /api/loans` / `POST /api/loans/{id}/return` | 借出 / 归还 |
| `POST /api/repair-projects` | 修复立项（委托人、方案，项目编号唯一） |
| `POST /api/repair-projects/{id}/start` | 开工 |
| `POST /api/repair-projects/{id}/stages` | 登记阶段结果（可多次） |
| `POST /api/repair-projects/{id}/finish` | 修复完成 |
| `POST /api/repair-projects/{id}/accept` | 验收（PASS/CONDITIONAL） |
| `POST /api/repair-projects/{id}/cancel` | 作废 |

## 文物多角度影像归档（museum-2）

正面（FRONT）、背面（BACK）、底部（BOTTOM）、细部（DETAIL）照片按**拍摄批次**归档。

### 归档规则

- **逐张校验**：上传时逐张校验分辨率（默认不低于 800×800，可由 `museum.image.min-*-px` 调整）、
  拍摄时间（必填、格式 `yyyy-MM-ddTHH:mm:ss` 或 `yyyy-MM-dd HH:mm:ss`、不得晚于当前时间）、
  摄影者（必填）。批量上传中任意一张失败都不影响其余张，响应逐张给出具体原因。
- **同文件去重**：物理文件按 SHA-256 内容寻址落盘（`ab/cd/<hash>.ext`），同一文件重复上传
  只保留一份物理文件、新增一条影像引用（响应中 `duplicateFile=true`）。
- **修复阶段新版本**：同一修复阶段内补拍沿用当前版本号；换一个修复阶段（或非修复场景的新批次）
  必须形成新版本，版本号文物内从 1 递增，只追加、不覆盖。
- **作废不删原文件**：单张/整批作废只把引用与批次置 `VOID` 并登记原因、写台账；物理文件与引用行
  永久保留、可查不可再下载。
- **下载按岗位脱敏**：`RESTORER`（修复师）取原件；`CURATOR`（馆藏保管员）取长边≤1280 加水印的
  JPEG 副本；`VIEWER`（观摩访客/未声明岗位）取长边≤640 的更强脱敏副本，且版本清单中摄影者姓名
  脱敏为「姓\*\*」。原文件不对低权限岗位出库。
- **权限收紧闸门**：`PUT /api/policy/repair-photo-upload {"open":false}` 收紧文物修复权限后，
  影像上传在入口处整体停止、不产生任何批次/版本/文件；已确认的影像版本、物理文件与台账事实
  全部保留，仍可查询与下载。`open=true` 放开后继续按新版本归档。

### 影像接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/relics/{id}/image-batches` | multipart 批量上传一个拍摄批次；`files` 与同序的 `viewTypes/shotAts/photographers`，可选 `projectId/stageId/shotLocation/remark` |
| `GET  /api/relics/{id}/image-versions?role=` | 影像版本清单（按版本分组，按岗位脱敏摄影者） |
| `GET  /api/image-batches/{batchId}?role=` | 拍摄批次详情（批次头 + 逐张影像） |
| `GET  /api/images/{imageId}/download?role=` | 按岗位下载（原件或脱敏副本） |
| `POST /api/images/{imageId}/void` | 作废单张影像（原文件保留） |
| `POST /api/image-batches/{batchId}/void` | 作废整个拍摄批次 |
| `GET  /api/policy/repair-photo-upload` | 查看影像上传权限开关 |
| `PUT  /api/policy/repair-photo-upload` | 收紧（`open=false`）/ 放开（`open=true`），写台账留痕 |

批量上传响应示例（节选）：`versionNo`、`successCount/failedCount`，以及与文件同序的 `items`，
每项含 `index/fileName/viewType/success/duplicateFile/imageId/fileId/reason`。

### 起步幂等

`POST /api/bootstrap` 首次调用播种样本 `WW-2026-0001`（入藏 `RU-2026-0001` → 借出/归还
`LO-2026-0001` → 修复项目 `RP-2026-0001`，含两个修复阶段与验收）。

幂等保证：播种与 `t_bootstrap_lock`（单行、CHECK id=1）在同一事务提交；重复请求直接回读同一行返回
`created=false`，不会产生第二条文物/项目/台账记录；并发请求靠主键冲突整体回滚，已通过 8 并发测试验证。

### 测试

```bash
mvn test
```

- `BootstrapApiTest`：起步两次只产生一条全链路样本，台账动作顺序完整。
- `ConcurrentBootstrapTest`：8 并发同时起步，仅一次播种，全部命中同一条样本。
- `MuseumLedgerRuleTest`：编号唯一、入藏不重复、借还/修复/验收状态机不可跳步、撤销不抹除记录。
- `ImageArchiveTest`：四视角批次归档；分辨率/拍摄时间/摄影者/视角逐张返回失败原因；
  同文件重复上传磁盘仅一份；不同修复阶段形成新版本、同阶段补拍沿用版本；
  作废不删原文件；修复师取原件、保管员/访客取脱敏副本；权限收紧时上传停止且已确认版本保留。
