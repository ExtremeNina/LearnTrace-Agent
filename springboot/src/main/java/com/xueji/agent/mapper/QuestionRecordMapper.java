package com.xueji.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xueji.agent.domain.entity.QuestionRecord;
import com.xueji.agent.domain.vo.QuestionItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 题目记录 Mapper：拍照记录（question_record）与 AI 相似题（similar_question）的合并分页查询
 */
@Mapper
public interface QuestionRecordMapper extends BaseMapper<QuestionRecord> {

    String MERGED_FILTER = "<if test='date != null and date != \"\"'> AND DATE(created_at) = #{date} </if>"
            + "<if test='subject != null and subject != \"\"'> AND subject = #{subject} </if>"
            + "<if test='keyword != null and keyword != \"\"'> AND question_text LIKE CONCAT('%', #{keyword}, '%') </if>";

    /**
     * 拍照题目与 AI 相似题合并分页（相似题的 answer 映射为 correctAnswer，无图 / 无作答字段）
     */
    @Select("<script>"
            + "SELECT id, 'photo' AS source, question_text AS questionText, subject,"
            + " image_oss_key AS imageOssKey, correct_answer AS correctAnswer, analysis,"
            + " is_wrong AS isWrong, NULL AS userAnswer, NULL AS userNote, created_at AS createdAt, updated_at AS updatedAt"
            + " FROM question_record WHERE user_id = #{userId} AND deleted = 0" + MERGED_FILTER
            + " UNION ALL "
            + "SELECT id, 'similar_ai' AS source, question_text AS questionText, subject,"
            + " NULL AS imageOssKey, answer AS correctAnswer, analysis,"
            + " NULL AS isWrong, NULL AS userAnswer, NULL AS userNote, created_at AS createdAt, updated_at AS updatedAt"
            + " FROM similar_question WHERE user_id = #{userId} AND deleted = 0" + MERGED_FILTER
            + " ORDER BY createdAt DESC LIMIT #{size} OFFSET #{offset}"
            + "</script>")
    List<QuestionItemVO> listMerged(@Param("userId") Long userId, @Param("date") String date,
                                    @Param("subject") String subject, @Param("keyword") String keyword,
                                    @Param("size") int size, @Param("offset") long offset);

    @Select("<script>"
            + "SELECT COUNT(*) FROM ("
            + "SELECT created_at FROM question_record WHERE user_id = #{userId} AND deleted = 0" + MERGED_FILTER
            + " UNION ALL "
            + "SELECT created_at FROM similar_question WHERE user_id = #{userId} AND deleted = 0" + MERGED_FILTER
            + ") t"
            + "</script>")
    long countMerged(@Param("userId") Long userId, @Param("date") String date,
                     @Param("subject") String subject, @Param("keyword") String keyword);
}
