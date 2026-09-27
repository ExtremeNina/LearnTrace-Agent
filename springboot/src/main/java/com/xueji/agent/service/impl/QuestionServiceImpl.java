package com.xueji.agent.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xueji.agent.domain.entity.Conversation;
import com.xueji.agent.domain.entity.Message;
import com.xueji.agent.domain.entity.QuestionRecord;
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
 * 题目记录实现：保存数据取自 message 表事实源（最近的带图题目消息 + 其后的助手回答）
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
    public boolean saveFromConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw new BusinessException(404, "会话不存在");
        }

        // 最近一条带图的用户消息即题目（payload 内含 imageUrl / questionText）
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
        Message questionMessage = questionMessages.get(0);

        // 题目之后的第一条助手回答作为正确答案
        List<Message> answerMessages = messageMapper.selectList(new QueryWrapper<Message>()
                .eq("conversation_id", conversationId)
                .eq("role", "assistant")
                .gt("id", questionMessage.getId())
                .orderByAsc("id")
                .last("LIMIT 1"));

        JSONObject payload = parsePayload(questionMessage.getPayload());
        String imageUrl = payload.getStr("imageUrl", "");
        String questionText = payload.getStr("questionText", questionMessage.getContent());

        QuestionRecord record = new QuestionRecord()
                .setUserId(userId)
                .setImageOssKey(imageUrl)
                .setQuestionText(questionText)
                .setCorrectAnswer(answerMessages.isEmpty() ? null : answerMessages.get(0).getContent())
                .setAiStatus("SUCCESS")
                .setRecordStatus("SAVED")
                .setCreatedAt(LocalDateTime.now())
                .setUpdatedAt(LocalDateTime.now());
        questionRecordMapper.insert(record);
        log.info("题目已保存, userId={}, conversationId={}, recordId={}", userId, conversationId, record.getId());
        return true;
    }

    @Override
    public List<QuestionRecord> listByUser(Long userId) {
        return questionRecordMapper.selectList(new QueryWrapper<QuestionRecord>()
                .eq("user_id", userId)
                .eq("deleted", 0)
                .orderByDesc("id"));
    }

    @Override
    public QuestionRecord getDetail(Long userId, Long id) {
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
