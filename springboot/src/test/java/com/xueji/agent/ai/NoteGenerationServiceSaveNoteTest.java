package com.xueji.agent.ai;

import com.xueji.agent.ai.RagIngestService;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.Note;
import com.xueji.agent.mapper.NoteMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网课 AI 笔记落库状态单测（B28）：首次生成 save_status=0（笔记管理不展示），
 * 重生成时继承用户已保存状态（save_status=1），已保存的树节点不因重生成消失
 */
class NoteGenerationServiceSaveNoteTest {

    private final NoteMapper noteMapper = mock(NoteMapper.class);
    private final RagIngestService ragIngestService = mock(RagIngestService.class);
    private final NoteGenerationService service = new NoteGenerationService();

    private Course course() {
        return new Course().setId(101L).setUserId(5L).setTitle("线性代数第5讲");
    }

    private NoteGenerationService wired() {
        ReflectionTestUtils.setField(service, "noteMapper", noteMapper);
        ReflectionTestUtils.setField(service, "ragIngestService", ragIngestService);
        return service;
    }

    @Test
    void firstGenerationShouldDefaultToUnsaved() {
        when(noteMapper.selectList(any())).thenReturn(List.of());

        ReflectionTestUtils.invokeMethod(wired(), "saveNote", course(), "笔记内容", List.of());

        ArgumentCaptor<Note> captor = ArgumentCaptor.forClass(Note.class);
        verify(noteMapper).insert(captor.capture());
        assertThat(captor.getValue().getSaveStatus()).isZero();
    }

    @Test
    void regenerationShouldInheritSavedStatus() {
        Note oldSaved = new Note().setId(7L).setSaveStatus(1)
                .setCreatedAt(LocalDateTime.now()).setUpdatedAt(LocalDateTime.now());
        when(noteMapper.selectList(any())).thenReturn(List.of(oldSaved));

        ReflectionTestUtils.invokeMethod(wired(), "saveNote", course(), "新版笔记内容", List.of());

        ArgumentCaptor<Note> captor = ArgumentCaptor.forClass(Note.class);
        verify(noteMapper).insert(captor.capture());
        assertThat(captor.getValue().getSaveStatus()).isEqualTo(1);
        // 重生成同步移出旧向量，防止孤儿向量
        verify(ragIngestService).removeNote(7L);
    }

    @Test
    void regenerationWithoutSaveShouldStayUnsaved() {
        Note oldUnsaved = new Note().setId(7L).setSaveStatus(0)
                .setCreatedAt(LocalDateTime.now()).setUpdatedAt(LocalDateTime.now());
        when(noteMapper.selectList(any())).thenReturn(List.of(oldUnsaved));

        ReflectionTestUtils.invokeMethod(wired(), "saveNote", course(), "新版笔记内容", List.of());

        ArgumentCaptor<Note> captor = ArgumentCaptor.forClass(Note.class);
        verify(noteMapper).insert(captor.capture());
        assertThat(captor.getValue().getSaveStatus()).isZero();
    }
}
