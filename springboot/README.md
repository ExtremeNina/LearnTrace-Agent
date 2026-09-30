# 学迹 Agent 后端

AI 个人学习工作台「学迹」的后端工程，技术栈与规范见仓库根 `README.md`、`学迹PRD.md` 与 `agent.md`。

- 基于 Spring Boot 3.4 + MyBatis-Plus + MySQL(xueji) + Redis + RabbitMQ + Sa-Token + Spring AI(DeepSeek)
- 包路径：`com.xueji.agent`，结构对齐 PRD §13（domain 四子包收拢实体数据）
- 基路径原为模板项目（茶饮商城），业务代码已清理，仅保留基础设施配置

## 模块一览

| 包 | 职责 |
| --- | --- |
| `ai` | Spring AI 编排：流式对话、笔记生成（NoteGenerationService）、Agent 工具（QuestionSaveTool 等）、提示词集中于 `ai/prompt/AgentPrompts` |
| `controller` | REST 接口：auth / conversations / question / courses / notes / file |
| `service` / `service.impl` | 业务逻辑（题目记录、网课流水线、笔记分层树与知识联系等） |
| `mq` | RabbitMQ 消费者（网课处理流水线入口） |
| `config` | 线程池、MQ、Sa-Token、CORS 等基础设施配置 |
| `domain` | entity / dto / vo / enums |

## 启动与测试

```bash
mvn spring-boot:run   # 端口 9090，依赖本地 MySQL(xueji)/Redis/RabbitMQ 与 application-local.properties 密钥
mvn test              # 单元测试（当前 63 个）
```

增量 DDL 与种子数据见 `src/main/resources/sql/`。
