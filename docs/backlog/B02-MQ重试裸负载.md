# B02 会话重试走 MQ 裸 HashMap 负载

- 优先级：P1　|　模块：网课　|　来源：Standards 轴（agent.md 经验坑清单 + RabbitMQConfig 核实）
- 状态：待办

## 问题
`CourseServiceImpl.retry()`（L155-157）用 `HashMap` 裸负载投递 MQ，而全工程未配置 Jackson MessageConverter（默认 SimpleMessageConverter 走 JDK 序列化）。同文件 `upload()`（L89-93）已正确使用 JSON 字符串负载，两处不一致。

## 影响
重试链路大概率直接失败（消费者反序列化拒绝或类型不匹配），网课失败重试功能实际不可用。

## 建议
retry 改为与 upload 相同的 `JSONUtil.createObj().set(...).toString()` 字符串负载；补一条重试链路集成验证。
