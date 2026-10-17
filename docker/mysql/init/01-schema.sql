
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
DROP TABLE IF EXISTS `ai_model_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_model_config` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户',
  `name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '显示名（如：智谱 GLM）',
  `base_url` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '供应商 OpenAI 兼容端点',
  `api_key` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'API Key（明文存储，接口脱敏返回）',
  `model` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模型名（传给供应商的 model 参数，如 glm-4-flash）',
  `api_format` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'chat_completions' COMMENT 'API 格式（当前仅 chat_completions）',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '0 正常 / 1 已删除',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_name` (`user_id`,`name`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户自建 AI 模型配置（OpenAI 兼容）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `async_task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `async_task` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户',
  `task_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'VIDEO_PIPELINE / QUESTION_ANALYZE / NOTE_GENERATE',
  `biz_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'COURSE / QUESTION / NOTE',
  `biz_id` bigint unsigned NOT NULL COMMENT '业务 id',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / PROCESSING / SUCCESS / FAILED',
  `progress` tinyint NOT NULL DEFAULT '0' COMMENT '进度 0-100',
  `error_msg` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败原因',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_status` (`user_id`,`status`),
  KEY `idx_biz` (`biz_type`,`biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='异步任务';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `content_document`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `content_document` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `course_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `title` varchar(255) NOT NULL,
  `summary` text,
  `model_config_id` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_cd_course` (`course_id`)
) ENGINE=InnoDB AUTO_INCREMENT=44 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='内容理解语义层文档（B26）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `content_knowledge_point`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `content_knowledge_point` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `document_id` bigint NOT NULL,
  `name` varchar(255) NOT NULL,
  `detail` text,
  `time_sec` int DEFAULT NULL,
  `section_sort` int DEFAULT NULL,
  `important` tinyint DEFAULT NULL,
  `error_prone` tinyint DEFAULT NULL,
  `sort` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_ckp_doc` (`document_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2144 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ContentDocument 知识点（B26）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `content_section`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `content_section` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `document_id` bigint NOT NULL,
  `title` varchar(255) NOT NULL,
  `summary` text,
  `start_sec` int NOT NULL,
  `end_sec` int NOT NULL,
  `sort` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_cs_doc` (`document_id`)
) ENGINE=InnoDB AUTO_INCREMENT=311 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ContentDocument 章节（B26）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `conversation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `conversation` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户',
  `title` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '新对话' COMMENT '会话标题',
  `last_active_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近活跃时间（排序与 30 天清理依据）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_last` (`user_id`,`last_active_at`)
) ENGINE=InnoDB AUTO_INCREMENT=71 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `course`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `course` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户',
  `title` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标题',
  `subject` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '学科（上传时选择）',
  `expectations` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户期望（上传时填写，注入笔记生成提示词）',
  `note_style` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '笔记风格：FOLLOW=跟学型 / EXAM_SPRINT=考点速查型（NULL=默认跟学型）',
  `learner_goal` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '学习目的（意图三问卡采集）',
  `learner_level` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '知识水平（意图三问卡采集）',
  `model_config_id` bigint unsigned DEFAULT NULL COMMENT '笔记生成使用的模型配置（NULL = 系统默认）',
  `study_note` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '用户学习笔记（网课页随想，富文本 HTML）',
  `video_oss_key` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '视频 OSS 访问 URL（流水线阶段上传后写入）',
  `video_size` bigint DEFAULT NULL COMMENT '视频大小（字节）',
  `duration` int DEFAULT NULL COMMENT '时长（秒）',
  `last_position_sec` int DEFAULT NULL COMMENT '上次播放位置（秒，播放器定时上报）',
  `progress_pct` smallint DEFAULT NULL COMMENT '观看进度百分比（0~100，按位置 / 时长取整；无时长为 NULL）',
  `last_studied_at` datetime DEFAULT NULL COMMENT '最近一次播放上报时间',
  `key_points` text COLLATE utf8mb4_unicode_ci COMMENT '本课重点（LLM 从转写提炼，JSON 数组字符串）',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / PROCESSING / SUCCESS / FAILED',
  `error_msg` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败原因（截断上限 2000 与代码 ERROR_MSG_MAX 对齐）',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '0 正常 / 1 已删除',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `stage` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '处理阶段细分（PROCESSING 期间：UPLOADING/EXTRACTING/TRANSCRIBING/ANALYZING/NOTE_GENERATING）',
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`,`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=48 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='网课';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `course_chapter`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `course_chapter` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `course_id` bigint unsigned NOT NULL COMMENT '所属网课',
  `title` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '章节标题',
  `start_sec` int NOT NULL COMMENT '开始秒数',
  `end_sec` int DEFAULT NULL COMMENT '结束秒数',
  `summary` text COLLATE utf8mb4_unicode_ci COMMENT '章节摘要',
  `sort` int NOT NULL DEFAULT '0' COMMENT '排序',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_course` (`course_id`,`sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='网课章节';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `course_frame`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `course_frame` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `course_id` bigint unsigned NOT NULL COMMENT '网课ID',
  `time_sec` int NOT NULL COMMENT '帧在视频中的时间（秒）',
  `oss_key` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '帧图 OSS 访问 URL',
  `ocr_text` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '该帧 OCR 识别文本',
  `ocr_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '识别状态：PENDING/SUCCESS/FAILED',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_course` (`course_id`)
) ENGINE=InnoDB AUTO_INCREMENT=837 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='网课关键帧';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `course_quiz_question`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `course_quiz_question` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `course_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `question_text` text NOT NULL,
  `answer` text,
  `analysis` text,
  `source_sec` int DEFAULT NULL,
  `sort` int NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_cq_course` (`course_id`)
) ENGINE=InnoDB AUTO_INCREMENT=773 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='课程课后习题（出题 Agent 产物）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `course_transcript_segment`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `course_transcript_segment` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `course_id` bigint unsigned NOT NULL COMMENT '所属网课',
  `start_sec` int NOT NULL COMMENT '开始秒数',
  `end_sec` int DEFAULT NULL COMMENT '结束秒数',
  `text` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '分段文本',
  `sort` int NOT NULL DEFAULT '0' COMMENT '排序',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `text_corrected` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修正后文本（转写修正管线自动应用；NULL=无修正）',
  `correction_meta` text COLLATE utf8mb4_unicode_ci COMMENT '修正元数据 JSON（original/suggestion/evidence/status/source）',
  PRIMARY KEY (`id`),
  KEY `idx_course` (`course_id`,`sort`)
) ENGINE=InnoDB AUTO_INCREMENT=3572 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='转写分段';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `daily_briefing`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `daily_briefing` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户',
  `conversation_id` bigint unsigned DEFAULT NULL COMMENT '归属会话（NULL=旧数据未绑定）',
  `brief_date` date NOT NULL COMMENT '简报日期',
  `stats_json` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '生成时的学习统计快照（JSON）',
  `content` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '简报正文（LLM 生成）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_brief` (`user_id`,`conversation_id`,`brief_date`)
) ENGINE=InnoDB AUTO_INCREMENT=27 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='每日学习简报（惰性生成，当天缓存）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `invite_code`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `invite_code` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '涓婚敭',
  `code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '閭??鐮',
  `max_uses` int NOT NULL DEFAULT '1' COMMENT '鏈?ぇ浣跨敤娆℃暟',
  `used_count` int NOT NULL DEFAULT '0' COMMENT '宸蹭娇鐢ㄦ?鏁',
  `expires_at` datetime DEFAULT NULL COMMENT '杩囨湡鏃堕棿锛岀┖=涓嶈繃鏈',
  `remark` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '澶囨敞锛堝彂缁欒皝锛',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邀请码';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `learning_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `learning_record` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户',
  `event_type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'UPLOAD_COURSE / SAVE_QUESTION / CHAT_MESSAGE / GENERATE_NOTE / SAVE_SIMILAR_QUESTION 等',
  `resource_type` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'COURSE / QUESTION / NOTE / CONVERSATION',
  `resource_id` bigint unsigned DEFAULT NULL COMMENT '资源 id',
  `note_id` bigint unsigned DEFAULT NULL COMMENT '关联知识点页（如有）',
  `detail` json DEFAULT NULL COMMENT '扩展信息',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`,`created_at`),
  KEY `idx_resource` (`resource_type`,`resource_id`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学习轨迹事件';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `message`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `message` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `conversation_id` bigint unsigned NOT NULL COMMENT '所属会话',
  `role` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'USER / ASSISTANT / TOOL',
  `msg_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT / CONFIRM_CARD',
  `content` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户输入或助手正文（Markdown）',
  `payload` json DEFAULT NULL COMMENT 'CONFIRM_CARD：待确认操作数据；TOOL：调用摘要',
  `confirm_status` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '仅 CONFIRM_CARD：PENDING / CONFIRMED / DISMISSED',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `video_url` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户上传视频的 OSS 地址（对话视频转写）',
  `course_id` bigint DEFAULT NULL COMMENT '关联网课 ID（对话课程任务占位消息）',
  PRIMARY KEY (`id`),
  KEY `idx_conv` (`conversation_id`,`id`)
) ENGINE=InnoDB AUTO_INCREMENT=325 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话消息';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `note`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户',
  `title` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标题',
  `content` longtext COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Markdown 原文',
  `note_type` tinyint NOT NULL DEFAULT '1' COMMENT '1 普通笔记 / 2 知识点页',
  `parent_id` bigint unsigned DEFAULT NULL COMMENT '预留知识树，MVP 不使用',
  `source_type` tinyint NOT NULL DEFAULT '0' COMMENT '0 手动 / 1 网课生成',
  `save_status` tinyint NOT NULL DEFAULT '1' COMMENT '保存状态：0=AI生成未保存（笔记管理不展示） 1=已保存进笔记管理',
  `node_type` tinyint NOT NULL DEFAULT '0' COMMENT '节点类型：0笔记 1分组',
  `course_id` bigint unsigned DEFAULT NULL COMMENT '来源网课（网课 AI 笔记挂靠）',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '0 正常 / 1 已删除',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_type` (`user_id`,`note_type`,`updated_at`),
  KEY `idx_course` (`course_id`)
) ENGINE=InnoDB AUTO_INCREMENT=96 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='笔记 / 知识点页';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `note_link`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `note_link` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `note_id` bigint unsigned NOT NULL COMMENT '所属笔记页',
  `user_id` bigint unsigned NOT NULL COMMENT '冗余，便于反查与清理',
  `link_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'COURSE / QUESTION / NOTE',
  `target_id` bigint unsigned NOT NULL COMMENT '目标资源 id',
  `title` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '按钮显示文字',
  `remark` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '联系说明（这条联系相关的点）',
  `ts_sec` int DEFAULT NULL COMMENT '跳转时间（秒，仅网课知识联系）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_note` (`note_id`),
  KEY `idx_target` (`link_type`,`target_id`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识联系';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `question_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `question_record` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户',
  `image_oss_key` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '题目图片 OSS 对象键（拍照题；QuizAgent 出题无图片）',
  `ocr_text` mediumtext COLLATE utf8mb4_unicode_ci COMMENT 'OCR 原文',
  `question_text` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '清洗后的题目',
  `subject` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '学科（AI 保存时分类，可编辑）',
  `user_answer` text COLLATE utf8mb4_unicode_ci COMMENT '图片识别或用户补充的答案',
  `correct_answer` text COLLATE utf8mb4_unicode_ci COMMENT 'DeepSeek 给出，允许用户修改',
  `analysis` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '解答与错因分析',
  `is_wrong` tinyint DEFAULT NULL COMMENT '0 正确 / 1 错误 / NULL 未判定',
  `ai_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / PROCESSING / SUCCESS / FAILED',
  `record_status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT 分析完成待确认 / SAVED 已入库',
  `user_note` text COLLATE utf8mb4_unicode_ci COMMENT '用户备注',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '0 正常 / 1 已删除',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_status` (`user_id`,`record_status`,`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=39 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='题目记录（错题本 = SAVED 且 is_wrong=1 的视图）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `review_card`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `review_card` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户',
  `card_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '卡片类型：question / similar / note',
  `ref_id` bigint unsigned NOT NULL COMMENT '来源实体主键（question_record / similar_question / note）',
  `due_at` datetime NOT NULL COMMENT '下次到期时间',
  `interval_days` int NOT NULL DEFAULT '0' COMMENT '当前间隔（天）',
  `ease` decimal(4,2) NOT NULL DEFAULT '2.50' COMMENT '容易度（SM-2 简化，1.30~3.00）',
  `reps` int NOT NULL DEFAULT '0' COMMENT '复习次数',
  `lapses` int NOT NULL DEFAULT '0' COMMENT '生疏次数',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '0 队列中 / 1 已移出',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_card` (`user_id`,`card_type`,`ref_id`),
  KEY `idx_due` (`user_id`,`due_at`)
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='复习卡（间隔重复调度状态）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `review_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `review_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户',
  `card_id` bigint unsigned NOT NULL COMMENT '复习卡 ID',
  `grade` tinyint NOT NULL COMMENT '评分：0 生疏 / 1 模糊 / 2 熟练',
  `interval_after` int NOT NULL COMMENT '评分后间隔（天）',
  `reviewed_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '复习时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`,`reviewed_at`),
  KEY `idx_card` (`card_id`)
) ENGINE=InnoDB AUTO_INCREMENT=25 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='复习评分流水';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `similar_question`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `similar_question` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户',
  `source_question_id` bigint unsigned DEFAULT NULL COMMENT '来源题目（对话流保存时为空）',
  `question_text` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '相似题题目',
  `answer` text COLLATE utf8mb4_unicode_ci COMMENT '答案',
  `analysis` text COLLATE utf8mb4_unicode_ci COMMENT '解析',
  `subject` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '学科（AI 保存时分类，可编辑）',
  `conversation_id` bigint unsigned DEFAULT NULL COMMENT '生成时所处会话',
  `is_correct` tinyint DEFAULT NULL COMMENT '预留作答结果，二期使用',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '0 正常 / 1 已删除',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`,`created_at`),
  KEY `idx_source` (`source_question_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='相似题（仅保存用户确认后的）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `study_time_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `study_time_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NOT NULL,
  `study_date` date NOT NULL,
  `duration_sec` int NOT NULL DEFAULT '0' COMMENT '当日累计学习秒数',
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_date` (`user_id`,`study_date`)
) ENGINE=InnoDB AUTO_INCREMENT=22 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='学习时长日志（一天一行，前端心跳累计）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '涓婚敭',
  `username` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '鐧诲綍鍚',
  `password_hash` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'BCrypt 瀵嗙爜鍝堝笇',
  `nickname` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '鏄电О锛岄粯璁ゅ彇鐢ㄦ埛鍚',
  `phone` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '鎵嬫満鍙凤紙棰勭暀锛',
  `email` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '閭??锛堥?鐣欙級',
  `avatar_url` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '澶村儚鍦板潃',
  `bio` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '个人简介（选填）',
  `theme` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'LIGHT' COMMENT '界面主题：LIGHT / DARK',
  `notify_task_enabled` tinyint NOT NULL DEFAULT '1' COMMENT '任务完成/失败通知开关：0 关 / 1 开',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0 姝ｅ父 / 1 绂佺敤',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '0 正常 / 1 已注销',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鍒涘缓鏃堕棿',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '鏇存柊鏃堕棿',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB AUTO_INCREMENT=49 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user_model_pref`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_model_pref` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '所属用户',
  `module` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模块：chat / course_note / briefing',
  `config_id` bigint unsigned DEFAULT NULL COMMENT '模型配置 ID，NULL = 系统默认',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_module` (`user_id`,`module`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户按模块的模型偏好';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user_profile`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_profile` (
  `user_id` bigint NOT NULL,
  `grade_level` varchar(50) DEFAULT NULL,
  `level` varchar(50) DEFAULT NULL,
  `goal` varchar(255) DEFAULT NULL,
  `note` varchar(500) DEFAULT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='学习者画像（B26 阶段 3）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

