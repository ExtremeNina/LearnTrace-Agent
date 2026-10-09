package com.xueji.agent.service.impl;

import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.Note;
import com.xueji.agent.domain.entity.NoteLink;
import com.xueji.agent.domain.vo.NoteTreeNodeVO;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.ai.RagIngestService;
import com.xueji.agent.mapper.CourseMapper;
import com.xueji.agent.mapper.NoteLinkMapper;
import com.xueji.agent.mapper.NoteMapper;
import com.xueji.agent.mapper.QuestionRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 笔记服务：分层树组装、5 层限制、环校验、知识联系
 */
@ExtendWith(MockitoExtension.class)
class NoteServiceImplTest {

    private static final Long USER_ID = 5L;

    @Mock
    private NoteMapper noteMapper;

    @Mock
    private NoteLinkMapper noteLinkMapper;

    @Mock
    private CourseMapper courseMapper;

    @Mock
    private QuestionRecordMapper questionRecordMapper;

    @Mock
    private RagIngestService ragIngestService;

    @Mock
    private com.xueji.agent.service.ReviewService reviewService;

    @InjectMocks
    private NoteServiceImpl service;

    private Note node(long id, String title, int nodeType, Long parent) {
        return new Note()
                .setId(id)
                .setUserId(USER_ID)
                .setTitle(title)
                .setNodeType(nodeType)
                .setParentId(parent)
                .setDeleted(0);
    }

    // ---- buildTree ----

    @Test
    void buildTreeShouldNestGroupsAndNotes() {
        List<Note> all = List.of(
                node(5, "数学", 1, null),
                node(6, "大一上", 1, 5L),
                node(7, "高等数学", 1, 6L),
                node(11, "罗尔定理", 0, 7L),
                node(1, "AI 笔记", 0, null));

        List<NoteTreeNodeVO> tree = NoteServiceImpl.buildTree(all);

        assertThat(tree).hasSize(2); // 数学 + 根目录的 AI 笔记
        NoteTreeNodeVO math = tree.get(0).getTitle().equals("数学") ? tree.get(0) : tree.get(1);
        assertThat(math.getChildren()).hasSize(1); // 大一上
        NoteTreeNodeVO up1 = math.getChildren().get(0);
        assertThat(up1.getChildren()).hasSize(1); // 高等数学
        assertThat(up1.getChildren().get(0).getChildren()).hasSize(1); // 罗尔定理
        assertThat(up1.getChildren().get(0).getChildren().get(0).getTitle()).isEqualTo("罗尔定理");
    }

    @Test
    void buildTreeShouldMoveOrphansToRoot() {
        List<Note> all = List.of(
                node(11, "孤儿笔记", 0, 999L), // 父分组不存在
                node(1, "AI 笔记", 0, null));

        List<NoteTreeNodeVO> tree = NoteServiceImpl.buildTree(all);

        assertThat(tree).hasSize(2); // 孤儿不丢弃，挂根目录
    }

    // ---- 层级限制 ----

    @Test
    void createGroupBeyondFiveLevelsShouldReject() {
        // 五级链：5→6→7→8→9（均已 5 层），在第 5 层分组下再建分组会超过 5 层
        List<Note> chain = List.of(
                node(5, "L1", 1, null), node(6, "L2", 1, 5L), node(7, "L3", 1, 6L),
                node(8, "L4", 1, 7L), node(9, "L5", 1, 8L));
        for (Note n : chain) {
            lenient().when(noteMapper.selectById(n.getId())).thenReturn(n);
        }
        // 5 层分组的子层级 = 6 > 5，应拒绝
        assertThatThrownBy(() -> service.createGroup(USER_ID, 9L, "L6"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void createGroupAtFourthLevelShouldPass() {
        List<Note> chain = List.of(
                node(5, "L1", 1, null), node(6, "L2", 1, 5L), node(7, "L3", 1, 6L),
                node(8, "L4", 1, 7L));
        for (Note n : chain) {
            lenient().when(noteMapper.selectById(n.getId())).thenReturn(n);
        }

        service.createGroup(USER_ID, 8L, "L5");

        ArgumentCaptor<Note> captor = ArgumentCaptor.forClass(Note.class);
        verify(noteMapper).insert(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("L5");
        assertThat(captor.getValue().getParentId()).isEqualTo(8L);
    }

    // ---- 移动 ----

    @Test
    void moveIntoOwnDescendantShouldReject() {
        // 数学(5) 移动到 大一上(6)——6 是 5 的子分组，形成环
        Note math = node(5, "数学", 1, null);
        Note up1 = node(6, "大一上", 1, 5L);
        List<Note> all = new ArrayList<>(List.of(math, up1));
        Map<Long, List<Note>> byParent = new HashMap<>();
        byParent.put(5L, new ArrayList<>(List.of(up1)));

        when(noteMapper.selectById(5L)).thenReturn(math);
        when(noteMapper.selectById(6L)).thenReturn(up1);
        lenient().when(noteMapper.selectList(any())).thenReturn(all);

        assertThatThrownBy(() -> service.move(USER_ID, 5L, 6L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void moveDeepSubtreeBeyondLimitShouldReject() {
        // 把子树高度 2 的"高等数学"(9) 移到已位于第 4 层的分组(8) 下 → 4+2 > 5
        Note l1 = node(5, "L1", 1, null);
        Note l2 = node(6, "L2", 1, 5L);
        Note l3 = node(7, "L3", 1, 6L);
        Note target = node(8, "L4分组", 1, 7L);
        Note mathGroup = node(9, "高等数学", 1, 6L);
        Note child = node(11, "罗尔定理", 0, 9L);
        List<Note> all = new ArrayList<>(List.of(l1, l2, l3, target, mathGroup, child));

        when(noteMapper.selectById(9L)).thenReturn(mathGroup);
        when(noteMapper.selectById(7L)).thenReturn(l3);
        when(noteMapper.selectById(8L)).thenReturn(target);
        when(noteMapper.selectById(6L)).thenReturn(l2);
        when(noteMapper.selectById(5L)).thenReturn(l1);
        when(noteMapper.selectList(any())).thenReturn(all);

        assertThatThrownBy(() -> service.move(USER_ID, 9L, 8L))
                .isInstanceOf(BusinessException.class);
    }

    // ---- 知识联系 ----

    @Test
    void addLinkShouldResolveCourseTitle() {
        Note boolNote = node(14, "布尔逻辑与逻辑门", 0, 10L);
        when(noteMapper.selectById(14L)).thenReturn(boolNote);
        com.xueji.agent.domain.entity.Course course = new com.xueji.agent.domain.entity.Course()
                .setId(14L).setTitle("计算机科学 第 3 讲：布尔逻辑与逻辑门");
        when(courseMapper.selectById(14L)).thenReturn(course);

        service.addLink(USER_ID, 14L, "course", 14L, 258, "课程中用与非门演示了或非的实现");

        ArgumentCaptor<NoteLink> captor = ArgumentCaptor.forClass(NoteLink.class);
        verify(noteLinkMapper).insert(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("计算机科学 第 3 讲：布尔逻辑与逻辑门");
        assertThat(captor.getValue().getRemark()).isEqualTo("课程中用与非门演示了或非的实现");
        assertThat(captor.getValue().getTsSec()).isEqualTo(258);
        assertThat(captor.getValue().getNoteId()).isEqualTo(14L);
    }

    @Test
    void addLinkShouldRejectUnknownType() {
        Note boolNote = node(14, "布尔逻辑与逻辑门", 0, 10L);
        when(noteMapper.selectById(14L)).thenReturn(boolNote);

        assertThatThrownBy(() -> service.addLink(USER_ID, 14L, "video", 1L, null, null))
                .isInstanceOf(BusinessException.class);
        verify(noteLinkMapper, never()).insert(any(NoteLink.class));
    }

    @Test
    void updateLinkRemarkShouldCheckOwnership() {
        NoteLink foreign = new NoteLink().setId(1L).setNoteId(14L).setUserId(999L);
        when(noteLinkMapper.selectById(1L)).thenReturn(foreign);

        assertThatThrownBy(() -> service.updateLinkRemark(USER_ID, 14L, 1L, "说明"))
                .isInstanceOf(BusinessException.class);
        verify(noteLinkMapper, never()).updateById(any(NoteLink.class));
    }

    @Test
    void updateLinkRemarkShouldSaveTrimmed() {
        NoteLink mine = new NoteLink().setId(1L).setNoteId(14L).setUserId(USER_ID);
        when(noteLinkMapper.selectById(1L)).thenReturn(mine);
        when(noteLinkMapper.updateById(any(NoteLink.class))).thenReturn(1);

        service.updateLinkRemark(USER_ID, 14L, 1L, "  罗尔定理是拉格朗日的特例  ");

        assertThat(mine.getRemark()).isEqualTo("罗尔定理是拉格朗日的特例");
        verify(noteLinkMapper).updateById(mine);
    }

    @Test
    void removeLinkShouldCheckOwnership() {
        NoteLink foreign = new NoteLink().setId(1L).setNoteId(14L).setUserId(999L);
        when(noteLinkMapper.selectById(1L)).thenReturn(foreign);

        assertThatThrownBy(() -> service.removeLink(USER_ID, 14L, 1L))
                .isInstanceOf(BusinessException.class);
        verify(noteLinkMapper, never()).deleteById(1L);
    }

    @Test
    void deleteGroupShouldCascadeDescendantsAndLinks() {
        Note root = node(10, "数学", 1, null);
        Note sub = node(11, "大一上", 1, 10L);
        Note leafA = node(12, "笔记A", 0, 10L);
        Note leafB = node(13, "笔记B", 0, 11L);
        when(noteMapper.selectById(10L)).thenReturn(root);
        when(noteMapper.selectList(any())).thenReturn(List.of(root, sub, leafA, leafB));
        when(noteLinkMapper.delete(any())).thenReturn(2);

        service.delete(USER_ID, 10L);

        // 整棵子树（含分组自身）一次性批量软删
        verify(noteMapper).update(isNull(), any());
        // 知识联系双向清理：被删笔记身上的联系 + 指向被删笔记的 note 型联系
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<NoteLink>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(noteLinkMapper).delete(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertThat(sql).contains("note_id IN");
        assertThat(sql).contains("link_type");
        assertThat(sql).contains("target_id IN");
    }

    @Test
    void deleteNoteShouldCleanItsLinks() {
        Note leaf = node(12, "笔记A", 0, 10L);
        when(noteMapper.selectById(12L)).thenReturn(leaf);
        when(noteLinkMapper.delete(any())).thenReturn(1);

        service.delete(USER_ID, 12L);

        verify(noteMapper).updateById(leaf);
        assertThat(leaf.getDeleted()).isEqualTo(1);
        verify(noteLinkMapper).delete(any());
        verify(noteMapper, never()).update(isNull(), any());
    }

    // ---- 网课 AI 笔记保存状态（B28）----

    @Test
    void treeShouldFilterBySaveStatus() {
        // 树查询必须带 save_status=1 条件：网课 AI 笔记（save_status=0）不在笔记管理展示
        when(noteMapper.selectList(any())).thenReturn(List.of());

        service.tree(USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Note>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(noteMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertThat(sql).contains("save_status");
        assertThat(sql).contains("deleted");
    }

    @Test
    void saveAiNoteToWorkspaceShouldFlipStatusToSaved() {
        Course course = new Course().setId(101L).setUserId(USER_ID);
        when(courseMapper.selectById(101L)).thenReturn(course);
        when(noteMapper.selectCount(any())).thenReturn(2L);
        when(noteMapper.update(isNull(), any())).thenReturn(2);

        assertThat(service.saveAiNoteToWorkspace(USER_ID, 101L)).isEqualTo("SAVED");
        verify(noteMapper).update(isNull(), any());
    }

    @Test
    void saveAiNoteToWorkspaceShouldBeIdempotentWhenAlreadySaved() {
        Course course = new Course().setId(101L).setUserId(USER_ID);
        when(courseMapper.selectById(101L)).thenReturn(course);
        // 存在 AI 笔记但全部已是 save_status=1 → update 命中 0 行 → 幂等返回
        when(noteMapper.selectCount(any())).thenReturn(1L);
        when(noteMapper.update(isNull(), any())).thenReturn(0);

        assertThat(service.saveAiNoteToWorkspace(USER_ID, 101L)).isEqualTo("ALREADY_SAVED");
    }

    @Test
    void saveAiNoteToWorkspaceShouldReturnNotFoundWhenNoAiNote() {
        Course course = new Course().setId(101L).setUserId(USER_ID);
        when(courseMapper.selectById(101L)).thenReturn(course);
        when(noteMapper.selectCount(any())).thenReturn(0L);

        assertThat(service.saveAiNoteToWorkspace(USER_ID, 101L)).isEqualTo("NOT_FOUND");
        verify(noteMapper, never()).update(isNull(), any());
    }

    @Test
    void saveAiNoteToWorkspaceShouldCheckOwnership() {
        Course foreign = new Course().setId(101L).setUserId(999L);
        when(courseMapper.selectById(101L)).thenReturn(foreign);

        assertThatThrownBy(() -> service.saveAiNoteToWorkspace(USER_ID, 101L))
                .isInstanceOf(BusinessException.class);
        verify(noteMapper, never()).update(isNull(), any());
    }
}
