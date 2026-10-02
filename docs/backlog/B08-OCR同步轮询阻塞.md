# B08 拍照解题 OCR 在请求线程内同步轮询

- 优先级：P2　|　模块：题目　|　来源：Spec 轴（PRD §16）+ AgentChatServiceImpl
- 状态：已完成（2026-10-02）

## 问题
带图消息的 OCR 前置流水线在对话请求线程内同步调用 PaddleOCR 并轮询结果（最长 120s），与 §16“长耗时任务走 RabbitMQ、不阻塞请求”不符。

## 影响
拍照解题时用户长时间无响应反馈；占用 WS/请求线程。

## 整改（2026-10-02）
采用建议二的增强版（不改产品交互流，LLM 回合仍以 OCR 结果为前置）：
- **移出请求线程**：`AgentChatServiceImpl.chat()` 整体改为 `Flux.defer(...).subscribeOn(Schedulers.boundedElastic())`，
  OCR 轮询、消息落库、记忆重建都在弹性线程执行，WS 线程只做装配与订阅。
- **缩短轮询上限**：PaddleOcrTool 轮询上限由固定 120s 改为可配置（`paddle-ocr.poll-timeout-seconds`，默认 45s），
  超时抛出异常走既有降级路径（识别失败提示 → 引导重传或直接输入题目文字）。
- 未选 MQ 异步方案的原因：LLM 回合的 prompt 依赖 OCR 结果，拆成 MQ 任务会把一个回合切成两段交互，
  与现有回合互斥 / 事实源落库时序强耦合；45s 上限 + 线程隔离已消除阻塞危害，符合“够用即可”。
