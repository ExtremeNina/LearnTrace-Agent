package com.xueji.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.domain.entity.Course;
import com.xueji.agent.domain.entity.Note;
import com.xueji.agent.domain.entity.NoteLink;
import com.xueji.agent.domain.vo.NoteTreeNodeVO;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.CourseMapper;
import com.xueji.agent.mapper.NoteLinkMapper;
import com.xueji.agent.mapper.NoteMapper;
import com.xueji.agent.mapper.QuestionRecordMapper;
import com.xueji.agent.service.NoteService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 笔记服务实现：OneNote 式分层（分组自定义、最多 5 层）+ 知识联系。
 * 分组与笔记统一存于 note 表（node_type 区分），parent_id 指向父分组（根为 NULL）。
 */
@Slf4j
@Service
public class NoteServiceImpl implements NoteService {

    /** 分层上限 */
    public static final int MAX_LEVELS = 5;
    /** 节点类型 */
    public static final int TYPE_NOTE = 0;
    public static final int TYPE_GROUP = 1;

    @Resource
    private NoteMapper noteMapper;

    @Resource
    private NoteLinkMapper noteLinkMapper;

    @Resource
    private CourseMapper courseMapper;

    @Resource
    private QuestionRecordMapper questionRecordMapper;

    // ---- 树 ----

    @Override
    public List<NoteTreeNodeVO> tree(Long userId) {
        List<Note> all = noteMapper.selectList(new QueryWrapper<Note>()
                .eq("user_id", userId)
                .eq("deleted", 0)
                .orderByAsc("created_at"));
        return buildTree(all);
    }

    /**
     * 组装分层树：分组嵌套、笔记为叶子；孤儿节点（父分组缺失）挂根目录。
     * 公开静态方法，便于单元测试
     */
    public static List<NoteTreeNodeVO> buildTree(List<Note> all) {
        Map<Long, NoteTreeNodeVO> voById = new HashMap<>();
        for (Note n : all) {
            NoteTreeNodeVO vo = new NoteTreeNodeVO();
            vo.setId(n.getId());
            vo.setTitle(n.getTitle());
            vo.setType(n.getNodeType() != null && n.getNodeType() == TYPE_GROUP ? "group" : "note");
            vo.setSource(n.getNodeType() != null && n.getNodeType() == TYPE_GROUP ? null : sourceName(n.getSourceType()));
            vo.setUpdatedAt(n.getUpdatedAt() == null ? null : n.getUpdatedAt().toString().replace('T', ' ').substring(0, 16));
            voById.put(n.getId(), vo);
        }
        List<NoteTreeNodeVO> roots = new ArrayList<>();
        for (Note n : all) {
            NoteTreeNodeVO vo = voById.get(n.getId());
            NoteTreeNodeVO parentVo = n.getParentId() == null ? null : voById.get(n.getParentId());
            // 父分组缺失（已删除等）时挂根目录，孤儿不丢弃
            if (parentVo == null || parentVo.getType().equals("note")) {
                roots.add(vo);
            } else {
                parentVo.getChildren().add(vo);
            }
        }
        return roots;
    }

    private static String sourceName(Integer sourceType) {
        return sourceType != null && sourceType == 1 ? "AI 生成" : "手动创建";
    }

    // ---- 详情 ----

    @Override
    public Map<String, Object> detail(Long userId, Long noteId) {
        Note note = ownedNote(userId, noteId);
        if (note.getNodeType() != null && note.getNodeType() == TYPE_GROUP) {
            throw new BusinessException("分组没有详情内容");
        }
        List<NoteLink> links = noteLinkMapper.selectList(new QueryWrapper<NoteLink>()
                .eq("note_id", noteId)
                .orderByAsc("id"));

        String courseTitle = null;
        if (note.getCourseId() != null) {
            Course course = courseMapper.selectById(note.getCourseId());
            if (course != null) {
                courseTitle = course.getTitle();
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("id", note.getId());
        result.put("title", note.getTitle());
        result.put("content", note.getContent());
        result.put("source", sourceName(note.getSourceType()));
        result.put("updatedAt", note.getUpdatedAt() == null ? null : note.getUpdatedAt().toString().replace('T', ' ').substring(0, 16));
        result.put("links", links);
        result.put("courseId", note.getCourseId());
        result.put("courseTitle", courseTitle);
        return result;
    }

    // ---- 新建 ----

    @Override
    public Long createGroup(Long userId, Long parentId, String name) {
        return createNode(userId, parentId, name, TYPE_GROUP, "");
    }

    @Override
    public Long createNote(Long userId, Long parentId, String title) {
        return createNode(userId, parentId, title, TYPE_NOTE, "");
    }

    private Long createNode(Long userId, Long parentId, String name, int nodeType, String content) {
        validateDepth(userId, parentId);
        Note node = new Note()
                .setUserId(userId)
                .setTitle(name)
                .setContent(content)
                .setNoteType(0)
                .setNodeType(nodeType)
                .setSourceType(0)
                .setParentId(parentId)
                .setCreatedAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now());
        noteMapper.insert(node);
        return node.getId();
    }

    // ---- 重命名 / 移动 / 删除 ----

    @Override
    public void rename(Long userId, Long id, String name) {
        Note node = ownedNote(userId, id);
        node.setTitle(name).setUpdatedAt(LocalDateTime.now());
        noteMapper.updateById(node);
    }

    @Override
    public void move(Long userId, Long id, Long parentId) {
        Note node = ownedNote(userId, id);
        Note newParent = null;
        if (parentId != null) {
            newParent = ownedNote(userId, parentId);
            if (newParent.getNodeType() == null || newParent.getNodeType() != TYPE_GROUP) {
                throw new BusinessException("只能移动到分组下");
            }
            if (parentId.equals(id)) {
                throw new BusinessException("不能移动到自身");
            }
        }
        // 环校验：目标分组不能是自己的子孙节点
        if (newParent != null && isDescendant(newParent, id)) {
            throw new BusinessException("不能移动到自己的子分组中");
        }
        // 层级校验：移动后的子树深度不能超过 5 层
        List<Note> all = noteMapper.selectList(new QueryWrapper<Note>()
                .eq("user_id", userId)
                .eq("deleted", 0));
        Map<Long, List<Note>> byParent = new HashMap<>();
        for (Note n : all) {
            byParent.computeIfAbsent(n.getParentId(), k -> new ArrayList<>()).add(n);
        }
        int baseDepth = newParent == null ? 0 : depthOf(newParent, userId);
        if (baseDepth + subtreeHeight(node, byParent) > MAX_LEVELS) {
            throw new BusinessException("最多支持 " + MAX_LEVELS + " 层，无法移动到该分组");
        }
        node.setParentId(parentId).setUpdatedAt(LocalDateTime.now());
        noteMapper.updateById(node);
    }

    @Override
    public void delete(Long userId, Long id) {
        Note node = ownedNote(userId, id);
        List<Long> groupIds = new ArrayList<>();
        List<Long> noteIds = new ArrayList<>();
        if (node.getNodeType() != null && node.getNodeType() == TYPE_GROUP) {
            // 分组删除：递归收集整棵子树（分组 + 笔记），全部软删
            Map<Long, List<Note>> childrenMap = new HashMap<>();
            List<Note> all = noteMapper.selectList(new QueryWrapper<Note>()
                    .eq("user_id", userId)
                    .eq("deleted", 0));
            for (Note n : all) {
                if (n.getParentId() == null) {
                    continue;
                }
                List<Note> children = childrenMap.get(n.getParentId());
                if (children == null) {
                    children = new ArrayList<>();
                    childrenMap.put(n.getParentId(), children);
                }
                children.add(n);
            }
            java.util.Deque<Long> queue = new java.util.ArrayDeque<>();
            queue.push(id);
            while (!queue.isEmpty()) {
                Long current = queue.pop();
                groupIds.add(current);
                List<Note> children = childrenMap.get(current);
                if (children == null) {
                    continue;
                }
                for (Note child : children) {
                    if (child.getNodeType() != null && child.getNodeType() == TYPE_GROUP) {
                        queue.push(child.getId());
                    } else {
                        noteIds.add(child.getId());
                    }
                }
            }
            List<Long> allIds = new ArrayList<>(groupIds);
            allIds.addAll(noteIds);
            noteMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Note>()
                    .in("id", allIds)
                    .set("deleted", 1)
                    .set("updated_at", LocalDateTime.now()));
        } else {
            noteIds.add(id);
            node.setDeleted(1).setUpdatedAt(LocalDateTime.now());
            noteMapper.updateById(node);
        }
        // 清理知识联系：被删笔记身上的联系 + 其他笔记指向被删笔记的 note 型联系
        if (!noteIds.isEmpty()) {
            noteLinkMapper.delete(new QueryWrapper<NoteLink>()
                    .in("note_id", noteIds)
                    .or()
                    .eq("link_type", "note")
                    .in("target_id", noteIds));
        }
    }

    // ---- 正文 ----

    @Override
    public void updateContent(Long userId, Long noteId, String content) {
        Note note = ownedNote(userId, noteId);
        if (note.getNodeType() != null && note.getNodeType() == TYPE_GROUP) {
            throw new BusinessException("分组没有正文");
        }
        note.setContent(content == null ? "" : content).setUpdatedAt(LocalDateTime.now());
        noteMapper.updateById(note);
    }

    // ---- 知识联系 ----

    @Override
    public void addLink(Long userId, Long noteId, String linkType, Long targetId, Integer tsSec, String remark) {
        Note note = ownedNote(userId, noteId);
        if (note.getNodeType() != null && note.getNodeType() == TYPE_GROUP) {
            throw new BusinessException("分组不能添加知识联系");
        }
        if (!"course".equals(linkType) && !"question".equals(linkType) && !"note".equals(linkType)) {
            throw new BusinessException("不支持的知识联系类型");
        }
        String title = resolveLinkTitle(linkType, targetId);
        NoteLink link = new NoteLink()
                .setNoteId(noteId)
                .setUserId(userId)
                .setLinkType(linkType)
                .setTargetId(targetId)
                .setTitle(title)
                .setRemark(remark == null || remark.isBlank() ? null : remark.trim())
                .setTsSec(tsSec)
                .setCreatedAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now());
        noteLinkMapper.insert(link);
    }

    @Override
    public void updateLinkRemark(Long userId, Long noteId, Long linkId, String remark) {
        NoteLink link = noteLinkMapper.selectById(linkId);
        if (link == null || !link.getNoteId().equals(noteId) || !link.getUserId().equals(userId)) {
            throw new BusinessException(404, "知识联系不存在");
        }
        link.setRemark(remark == null || remark.isBlank() ? null : remark.trim())
                .setUpdatedAt(LocalDateTime.now());
        noteLinkMapper.updateById(link);
    }

    @Override
    public void removeLink(Long userId, Long noteId, Long linkId) {
        NoteLink link = noteLinkMapper.selectById(linkId);
        if (link == null || !link.getNoteId().equals(noteId) || !link.getUserId().equals(userId)) {
            throw new BusinessException(404, "知识联系不存在");
        }
        noteLinkMapper.deleteById(linkId);
    }

    /**
     * 按类型解析知识联系的展示标题（后端统一生成，前端不传标题）
     */
    private String resolveLinkTitle(String linkType, Long targetId) {
        if ("course".equals(linkType)) {
            Course course = courseMapper.selectById(targetId);
            if (course != null) {
                return course.getTitle();
            }
        } else if ("note".equals(linkType)) {
            Note note = noteMapper.selectById(targetId);
            if (note != null) {
                return note.getTitle();
            }
        } else if ("question".equals(linkType)) {
            var question = questionRecordMapper.selectById(targetId);
            if (question != null && question.getQuestionText() != null) {
                String text = question.getQuestionText().replaceAll("\\s+", " ").trim();
                return text.length() > 30 ? text.substring(0, 30) + "…" : text;
            }
        }
        throw new BusinessException("知识联系的目标不存在");
    }

    // ---- 校验工具 ----

    private Note ownedNote(Long userId, Long id) {
        Note note = noteMapper.selectById(id);
        if (note == null || !note.getUserId().equals(userId) || Integer.valueOf(1).equals(note.getDeleted())) {
            throw new BusinessException(404, "笔记不存在");
        }
        return note;
    }

    /**
     * 层级校验：目标父分组的子层级不能超过 5 层（父为 null 表示根目录，恒通过）
     */
    private void validateDepth(Long userId, Long parentId) {
        if (parentId == null) {
            return;
        }
        Note parent = ownedNote(userId, parentId);
        if (parent.getNodeType() == null || parent.getNodeType() != TYPE_GROUP) {
            throw new BusinessException("只能创建在分组下");
        }
        if (depthOf(parent, userId) + 1 > MAX_LEVELS) {
            throw new BusinessException("最多支持 " + MAX_LEVELS + " 层，无法在更深层继续创建");
        }
    }

    /**
     * 节点在其所属分层树中的层级（根的子节点为 1）
     */
    private int depthOf(Note node, Long userId) {
        int depth = 1;
        Set<Long> visited = new HashSet<>();
        visited.add(node.getId());
        Long cursor = node.getParentId();
        while (cursor != null && !visited.contains(cursor)) {
            visited.add(cursor);
            depth++;
            Note parent = noteMapper.selectById(cursor);
            if (parent == null) {
                break;
            }
            cursor = parent.getParentId();
        }
        return depth;
    }

    private boolean isDescendant(Note candidate, Long ancestorId) {
        Set<Long> visited = new HashSet<>();
        Long cursor = candidate.getParentId();
        while (cursor != null && !visited.contains(cursor)) {
            if (cursor.equals(ancestorId)) {
                return true;
            }
            visited.add(cursor);
            Note parent = noteMapper.selectById(cursor);
            if (parent == null) {
                break;
            }
            cursor = parent.getParentId();
        }
        return false;
    }

    /**
     * 节点子树高度（节点自身为 1 层）。公开静态方法，便于单元测试
     */
    public static int subtreeHeight(Note node, Map<Long, List<Note>> byParent) {
        int max = 0;
        List<Note> children = byParent.get(node.getId());
        if (children != null) {
            for (Note child : children) {
                int h = subtreeHeight(child, byParent);
                if (h > max) {
                    max = h;
                }
            }
        }
        return 1 + max;
    }

    private int subtreeHeight(Note node) {
        return 1;
    }
}
