package com.xueji.agent.ai;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.OverAllStateFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SAA Graph 兼容性回归（B26 阶段 3 spike 固化）：
 * 验证 spring-ai-alibaba-graph-core 1.0.0.2 与当前 Spring AI 1.1.8 组合下
 * StateGraph（节点 / 条件边 / 状态合并 / invoke）运行时可用——生产 NotePipelineGraphRunner 依赖同样的 API 面
 */
class SaaGraphCompatibilityTest {

    @Test
    void stateGraphShouldRouteConditionallyAndMergeState() throws Exception {
        OverAllStateFactory factory = () -> new OverAllState()
                .registerKeyAndStrategy(Map.of(
                        "count", new ReplaceStrategy(),
                        "path", new ReplaceStrategy()));

        StateGraph graph = new StateGraph(factory)
                .addNode("count", AsyncNodeAction.node_async((NodeAction) state -> {
                    int current = state.<Integer>value("count").orElse(0);
                    return Map.of("count", current + 1);
                }))
                .addNode("finish", AsyncNodeAction.node_async((NodeAction) state ->
                        Map.of("path", "done")))
                .addEdge(START, "count")
                .addConditionalEdges("count", AsyncEdgeAction.edge_async((EdgeAction) state -> {
                    int count = state.<Integer>value("count").orElse(0);
                    return count >= 3 ? "finish" : "count";
                }), Map.of("count", "count", "finish", "finish"))
                .addEdge("finish", END);

        CompiledGraph compiled = graph.compile();
        OverAllState result = compiled.invoke(new HashMap<>())
                .orElseThrow(() -> new AssertionError("图执行未产出状态"));

        // 循环三次后条件路由到 finish，状态合并正确
        assertThat(result.<Integer>value("count").orElse(0)).isEqualTo(3);
        assertThat(result.<String>value("path").orElse("")).isEqualTo("done");
    }
}
