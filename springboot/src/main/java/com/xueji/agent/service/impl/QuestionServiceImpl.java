package com.xueji.agent.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.domain.dto.QuestionUpdateDto;
import com.xueji.agent.domain.entity.Conversation;
import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.domain.entity.QuestionRecord;
import com.xueji.agent.domain.vo.PageVO;
import com.xueji.agent.exception.BusinessException;
import com.xueji.agent.mapper.ConversationMapper;
import com.xueji.agent.mapper.MessageMapper;
import com.xueji.agent.mapper.QuestionRecordMapper;
import com.xueji.agent.service.QuestionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 题目记录实现：保存时 imageUrl 从 message 表 payload 确定性获取，
 * 题目 / 解答 / 错因内容由模型整理后传入
 */
@Slf4j
@Service
public class QuestionServiceImpl implements QuestionService {

    @Resource
    private QuestionRecordMapper questionRecordMapper;

    @Resource
    private ConversationMapper conversationMapper;

    @Resource
    private MessageMapper messageMapper;

    @Override
    public boolean saveFromConversation(Long userId, Long conversationId,
                                        String questionText, String correctAnswer, String analysis) {
        if (questionText == null || questionText.isBlank()) {
            return false;
        }
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw new BusinessException(404, "会话不存在");
        }

        // 最近一条带图的用户消息即题目来源（payload 内含 imageUrl）
        List<Message> questionMessages = messageMapper.selectList(new QueryWrapper<Message>()
                .eq("conversation_id", conversationId)
                .eq("role", "user")
                .isNotNull("payload")
                .like("payload", "imageUrl")
                .orderByDesc("id")
                .last("LIMIT 1"));
        if (questionMessages.isEmpty()) {
            return false;
        }
        String imageUrl = parsePayload(questionMessages.get(0).getPayload()).getStr("imageUrl", "");

        QuestionRecord record = new QuestionRecord()
                .setUserId(userId)
                .setImageOssKey(imageUrl)
                .setQuestionText(questionText)
                .setCorrectAnswer(correctAnswer)
                .setAnalysis(analysis)
                .setAiStatus("SUCCESS")
                .setRecordStatus("SAVED")
                .setCreatedAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now());
        questionRecordMapper.insert(record);
        log.info("题目已保存, userId={}, conversationId={}, recordId={}", userId, conversationId, record.getId());
        return true;
    }

    @Override
    public PageVO<QuestionRecord> listByUser(Long userId, String date, int page, int size) {
        QueryWrapper<QuestionRecord> wrapper = new QueryWrapper<QuestionRecord>()
                .eq("user_id", userId)
                .eq("deleted", 0);
        if (date != null && !date.isBlank()) {
            wrapper.apply("DATE(created_at) = {0}", date);
        }
        long total = questionRecordMapper.selectCount(wrapper);
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        wrapper.orderByDesc("id")
                .last("LIMIT " + safeSize + " OFFSET " + (long) (safePage - 1) * safeSize);
        List<QuestionRecord> list = questionRecordMapper.selectList(wrapper);
        return new PageVO<>(list, total, safePage, safeSize);
    }

    @Override
    public QuestionRecord getDetail(Long userId, Long id) {
        return requireOwnedRecord(userId, id);
    }

    @Override
    public QuestionRecord updateByUser(Long userId, Long id, QuestionUpdateDto dto) {
        QuestionRecord record = requireOwnedRecord(userId, id);
        if (dto.getQuestionText() != null) {
            record.setQuestionText(dto.getQuestionText());
        }
        if (dto.getUserAnswer() != null) {
            record.setUserAnswer(dto.getUserAnswer());
        }
        if (dto.getCorrectAnswer() != null) {
            record.setCorrectAnswer(dto.getCorrectAnswer());
        }
        if (dto.getUserNote() != null) {
            record.setUserNote(dto.getUserNote());
        }
        record.setUpdatedAt(LocalDateTime.now());
        questionRecordMapper.updateById(record);
        return record;
    }

    @Override
    public void deleteByUser(Long userId, Long id) {
        QuestionRecord record = requireOwnedRecord(userId, id);
        record.setDeleted(1);
        record.setUpdatedAt(LocalDateTime.now());
        questionRecordMapper.updateById(record);
    }

    /**
     * 取本人且未删除的记录，否则视为不存在
     */
    private QuestionRecord requireOwnedRecord(Long userId, Long id) {
        QuestionRecord record = questionRecordMapper.selectById(id);
        if (record == null || record.getDeleted() == 1 || !record.getUserId().equals(userId)) {
            throw new BusinessException(404, "题目不存在");
        }
        return record;
    }

    /**
     * 解析消息 payload JSON，异常时返回空对象
     */
    private JSONObject parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(payload);
        } catch (Exception e) {
            log.warn("payload 解析失败: {}", payload);
            return new JSONObject();
        }
    }
}
