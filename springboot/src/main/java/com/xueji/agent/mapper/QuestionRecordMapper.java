package com.xueji.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xueji.agent.domain.entity.QuestionRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 题目记录 Mapper
 */
@Mapper
public interface QuestionRecordMapper extends BaseMapper<QuestionRecord> {
}
