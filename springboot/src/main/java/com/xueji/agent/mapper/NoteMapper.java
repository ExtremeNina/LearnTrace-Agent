package com.xueji.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xueji.agent.domain.entity.Note;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NoteMapper extends BaseMapper<Note> {
}
