# MathStudyAgent 前后端接口交接文档 (backend-handoff.md)

编写日期：2026-10-07  
责任人：Gemini 前端实现团队  
交接对象：后续后端工程师 / Codex 复审团队

---

## 1. 严格遵守的后端边界原则
在本轮前端实现中，严格遵守交接要求：
- **不新增任何后端代码**，不启动新的服务端进程或编写未经审批的服务器逻辑。
- **不篡改数据库业务契约**，数据表版本与迁移策略保持完全一致。
- **不替换现有 AI 服务协议**，不注入新的商业生图服务依赖。
- 缺失或未接入的服务端能力，统一通过 UI Adapter 提供清晰的 `(State, Callback)` 接口，当缺少实现时优雅呈现“待接入”状态，严禁用虚假网络延迟模拟成功。

---

## 2. 前端已复用的既有客户端与后端接口

| 模块 | 已复用类 / 接口 | 用途与复用方式 |
| :--- | :--- | :--- |
| **会话与流式** | `ChatVM`, `ChatService`, `ConversationRepository` | 驱动 P01/P02 会话加载、流式生成、在途取消 (`stopGeneration`) 及分支树切换 |
| **学习记忆** | `LearningMemoryStore`, `MemoryReducer`, `MemoryFingerprints` | 驱动 P05/P06 记忆流式观察、更正 (`edit`)、删除及删除抑制 (`delete`) |
| **学习材料与成果** | `MaterialFiles`, `FilesManager`, `FileProvider` | 驱动 P03/P07 文件导入、SHA256 校验、本地磁盘存储、SAF 另存为与分享 |
| **模型与提供商** | `SettingsStore`, `ProviderSetting`, `Model` | 驱动 P11/P12/P13/P14 的多提供商存储、凭据加载、模型列表与能力管理 |
| **偏好与动效设置** | `PreferencesStore.displaySetting` | 驱动 P09 的 `reduceMotion`（默认开启）与明暗模式读取 |

---

## 3. 新 UI 暴露的状态与待后端接入接口契约

### 3.1 P04 原图与符号核对接口
- **界面组件**：`StudySymbolCheckSheet.kt`
- **当前接入方式**：
  ```kotlin
  fun StudySymbolCheckSheet(
      visible: Boolean,
      onDismissRequest: () -> Unit,
      originalImageUri: Uri?,
      latexCandidates: List<String>,
      onConfirm: (confirmedLatex: String) -> Unit,
      onPostpone: () -> Unit,
  )
  ```
- **待后端接入任务**：
  1. 提供 OCR 公式检测的置信度低于阈值时触发核对事件的推送流。
  2. 接收 `onConfirm(confirmedLatex)` 回传，将人工校验后的精确 LaTeX 字符串更新至会话上下文或材料特征索引中。

---

### 3.2 P08 成果导出与格式转换接口
- **界面组件**：`StudyArtifactPreviewPage.kt`
- **当前接入方式**：
  - 本地导出（Markdown 与文本）：通过 Android 原生 SAF（`CreateDocument`）由客户端直接完成本地文件写入。
  - Word / PDF 复杂排版导出：目前由 UI Adapter 提供不可用状态提示（“当前版本仅支持本地 Markdown 导出；高级格式待后端云端转换接入”）。
- **待后端接入任务**：
  1. 提供云端或本地端服务接口：`exportArtifact(fileId, format: "PDF" | "DOCX") -> JobResult`。
  2. 任务状态需提供标准的 `QUEUED` -> `PROCESSING` -> `SUCCEEDED` / `FAILED` 状态机流。

---

## 4. 状态语义与版本归属规范

1. **终端错误与取消语义**：
   - `CANCELLED`：用户主动点击停止。必须保留当前已收到的所有分块内容，不触发重试，吉祥物恢复平静姿态。
   - `INTERRUPTED`：网络中断、进程异常或心跳超时。必须保留已收到推导，允许用户点击“接续生成”或“重新生成”。
   - `FAILED`：明确返回服务端报错（如 401/403/429/500）。展示错误提示，不自动发起重试。

2. **数据版本归属（防覆盖与防幽灵）**：
   - 记忆更新必须携带 `revision: Int`。如果服务端或数据库版本高于当前视图 revision，必须返回乐观锁冲突异常，禁止盲目覆写。
   - 提供商编辑表单：测试连接与保存配置为双独立操作。旧版本的测试网络响应绝不能反向覆写用户已编辑并保存的新配置。

---

## 5. 预览 Fixture 与生产数据隔离

- **严格物理隔离**：
  - `StudyHandoffGalleryPage.kt` 内部所有展示用的 `mockProviders`、`mockFiles` 均封装在私有函数或 `@Composable` 局部作用域内。
  - 生产页面（`ChatPage`、`MaterialPage`、`LearningMemoryPage`）仅从 `Koin` 注入的 `SettingsStore`、`MaterialFiles` 和 `LearningMemoryStore` 读取真实的持久化数据。
  - 严禁生产代码从 Mock 类读取硬编码数据。
