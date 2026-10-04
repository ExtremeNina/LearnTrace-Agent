package com.xueji.agent.domain.vo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回合事件工厂方法的字段语义
 */
class ChatEventTest {

    @Test
    void deltaShouldCarryText() {
        ChatEvent event = ChatEvent.delta("t_1", "你好");
        assertThat(event.getType()).isEqualTo("DELTA");
        assertThat(event.getTurnId()).isEqualTo("t_1");
        assertThat(event.getText()).isEqualTo("你好");
        assertThat(event.getMessageId()).isNull();
    }

    @Test
    void completeShouldCarryMessageId() {
        ChatEvent event = ChatEvent.complete("t_1", 42L);
        assertThat(event.getType()).isEqualTo("COMPLETE");
        assertThat(event.getMessageId()).isEqualTo(42L);
    }

    @Test
    void errorShouldCarryCodeAndMessage() {
        ChatEvent event = ChatEvent.error("t_1", "TURN_IN_PROGRESS", "正在回复中");
        assertThat(event.getType()).isEqualTo("ERROR");
        assertThat(event.getCode()).isEqualTo("TURN_IN_PROGRESS");
        assertThat(event.getMessage()).isEqualTo("正在回复中");
    }

    @Test
    void stopShouldOnlyCarryTypeAndTurnId() {
        ChatEvent event = ChatEvent.stop("t_1");
        assertThat(event.getType()).isEqualTo("STOP");
        assertThat(event.getTurnId()).isEqualTo("t_1");
        assertThat(event.getText()).isNull();
    }

    @Test
    void transcribeShouldCarryProgress() {
        ChatEvent event = ChatEvent.transcribe(7L, "processing", null, 2, 5, null);
        assertThat(event.getType()).isEqualTo("TRANSCRIBE");
        assertThat(event.getMessageId()).isEqualTo(7L);
        assertThat(event.getStatus()).isEqualTo("processing");
        assertThat(event.getDone()).isEqualTo(2);
        assertThat(event.getTotal()).isEqualTo(5);
        assertThat(event.getText()).isNull();
    }

    @Test
    void transcribeDoneShouldCarryFullText() {
        ChatEvent event = ChatEvent.transcribe(7L, "done", "[00:00] 全文", 3, 3, null);
        assertThat(event.getStatus()).isEqualTo("done");
        assertThat(event.getText()).isEqualTo("[00:00] 全文");
        assertThat(event.getMessage()).isNull();
    }
}
