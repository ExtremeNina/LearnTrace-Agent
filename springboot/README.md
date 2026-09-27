# 学迹 Agent 后端

AI 个人学习工作台「学迹」的后端工程，技术栈与规范见仓库上层 `学迹PRD.md` 与 `agent.md`。

- 基于 Spring Boot 3.4 + MyBatis-Plus + MySQL(xueji) + Redis + Sa-Token
- 包路径：`com.xueji.agent`，结构对齐 PRD §13（domain 四子包收拢实体数据）
- 基路径原为模板项目（茶饮商城），业务代码已清理，仅保留基础设施配置
