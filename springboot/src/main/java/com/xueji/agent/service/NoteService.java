package com.xueji.agent.service;

import com.xueji.agent.domain.vo.NoteTreeNodeVO;

import java.util.List;
import java.util.Map;

/**
 * 笔记服务（PRD §3.4）：OneNote 式分层（分组自定义、最多 5 层）+ 知识联系
 */
public interface NoteService {

    /** 用户的笔记分层树（分组 + 笔记叶子） */
    List<NoteTreeNodeVO> tree(Long userId);

    /** 笔记详情：内容 + 知识联系 + 关联网课标题 */
    Map<String, Object> detail(Long userId, Long noteId);

    /** 新建分组，返回分组 ID */
    Long createGroup(Long userId, Long parentId, String name);

    /** 新建笔记，返回笔记 ID */
    Long createNote(Long userId, Long parentId, String title);

    /** 重命名（分组 / 笔记通用） */
    void rename(Long userId, Long id, String name);

    /** 移动节点到目标分组（null = 根目录），含 5 层与环校验 */
    void move(Long userId, Long id, Long parentId);

    /**
     * 删除：笔记软删并清理其知识联系；
     * 分组删除会递归删除其下所有子分组与笔记（一并清理知识联系），调用方须先向用户确认
     */
    void delete(Long userId, Long id);

    /** 更新笔记正文（在线编辑保存） */
    void updateContent(Long userId, Long noteId, String content);

    /**
     * 保存对话视频转写笔记（B11）：分组不存在时在根目录自动创建，笔记 sourceType=2（对话转写），
     * 落库后异步向量化。分组名 / 标题由 LLM 拟定并经用户对话确认
     */
    Long saveTranscriptNote(Long userId, String groupName, String title, String content);

    /**
     * 把网课 AI 笔记保存进笔记管理（B28）：该课程 sourceType=1 未删除的 AI 笔记 save_status 0→1，
     * 节点挂根目录（用户可自行拖拽分组）。课程不存在 / 无归属抛 BusinessException。
     *
     * @return "SAVED"=本次保存成功；"ALREADY_SAVED"=均已保存（幂等）；"NOT_FOUND"=该网课还没有 AI 笔记
     */
    String saveAiNoteToWorkspace(Long userId, Long courseId);

    /** 添加知识联系（linkType: course / question / note；remark 可选关联说明） */
    void addLink(Long userId, Long noteId, String linkType, Long targetId, Integer tsSec, String remark);

    /** 修改知识联系的说明（仅 remark） */
    void updateLinkRemark(Long userId, Long noteId, Long linkId, String remark);

    /** 删除知识联系 */
    void removeLink(Long userId, Long noteId, Long linkId);
}
