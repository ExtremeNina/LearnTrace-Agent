# 学迹 Agent 前端

AI 个人学习工作台「学迹」的前端工程：Vue 3 + TypeScript + Tailwind CSS 4 + Vite，桌面 / 移动端响应式（移动端 Sidebar 折叠为抽屉）。

项目总览、架构链路与后端接口见仓库根 `README.md`；开发规范见 `agent.md`。

## 启动

```bash
npm install
npm run dev      # 开发服务器，端口 5173
npm run build    # vue-tsc 类型检查 + vite 构建
```

直连后端 `http://localhost:9090`（后端 CORS 已放行 5173），无需前端代理；登录令牌存 localStorage（`xj_token`），请求头 `sa-token` 携带，WebSocket 走 query 参数。

## 目录结构

```text
src/
├── api/        # 后端接口封装：http.ts（axios 实例 + Result 解包 + token 注入）
│               # auth / conversation / question / course / note / upload
├── stores/     # Pinia：agent.ts（对话状态与流式事件）、auth.ts、ui.ts
├── ws/         # agentSocket.ts：WebSocket 封装（指数退避自动重连）
├── utils/      # markdown.ts：Markdown + KaTeX 渲染（DOMPurify 消毒，含历史消息格式粘连规整）
├── types/      # 后端实体与分页结构类型
├── layouts/    # MainLayout（桌面三栏 / 移动端抽屉）
├── components/ # IconRail（图标栏与设置弹窗）、SidebarContent 等
└── views/      # Agent（对话）/ History / Questions（拍照记录）/ Courses / CourseDetail / Notes / Login / Register
```

## 关键实现说明

- **流式对话**：`stores/agent.ts` 维护消息流与流式占位气泡，`ws/agentSocket.ts` 收发 `chat.send` / `chat.stop` 与 DELTA / COMPLETE / STOP / ERROR 事件；断线指数退避重连（上限 15s）；刷新页面后自动恢复上次会话（localStorage `xj_active_conversation`）
- **Markdown 渲染**：助手消息经 `marked` + `marked-katex-extension`（`$...$` / `$$...$$` 公式）渲染，DOMPurify 消毒后 `v-html`；渲染前对历史消息做格式粘连规整（行中 `###` 断行、列表项粘连断行）
- **拍照解题**：消息带图上传（`/upload/image`）→ 解答完成后动作条可复制 → 回复「保存」由后端 AI 工具落库 → 拍照记录页可筛选 / 编辑 / 删除
- **网课**：列表三状态卡片 + 上传弹窗（含用户期望输入）；详情页视频在线播放，AI 笔记 / 转写中的 `[mm:ss]` 时间戳点击跳转对应片段
- **测试脚本**：`test-ws.mjs`（Node 24+ 全局 WebSocket）——`node test-ws.mjs <token> <conversationId>` 端到端验证两轮对话与记忆
