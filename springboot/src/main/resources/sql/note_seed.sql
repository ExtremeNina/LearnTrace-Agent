-- 笔记分层测试数据（管理员 user_id=5）：数学分组树 + 计算机科学（内容取自网课 AI 笔记）
SET @u = 5;

-- 分组：数学 → 大一上 → 高等数学 / 线性代数；大一下；计算机科学
INSERT INTO note (user_id, title, content, note_type, node_type, parent_id, source_type, created_at, updated_at)
VALUES (@u, '数学', '', 1, 1, NULL, 0, NOW(), NOW());
SET @g_math = LAST_INSERT_ID();

INSERT INTO note (user_id, title, content, note_type, node_type, parent_id, source_type, created_at, updated_at)
VALUES (@u, '大一上', '', 1, 1, @g_math, 0, NOW(), NOW());
SET @g_up1 = LAST_INSERT_ID();

INSERT INTO note (user_id, title, content, note_type, node_type, parent_id, source_type, created_at, updated_at)
VALUES (@u, '高等数学', '', 1, 1, @g_up1, 0, NOW(), NOW());
SET @g_math2 = LAST_INSERT_ID();

INSERT INTO note (user_id, title, content, note_type, node_type, parent_id, source_type, created_at, updated_at)
VALUES (@u, '线性代数', '', 1, 1, @g_up1, 0, NOW(), NOW());
SET @g_linear = LAST_INSERT_ID();

INSERT INTO note (user_id, title, content, note_type, node_type, parent_id, source_type, created_at, updated_at)
VALUES (@u, '大一下', '', 1, 1, @g_math, 0, NOW(), NOW());

INSERT INTO note (user_id, title, content, note_type, node_type, parent_id, source_type, created_at, updated_at)
VALUES (@u, '计算机科学', '', 1, 1, NULL, 0, NOW(), NOW());
SET @g_cs = LAST_INSERT_ID();

-- 笔记：数学（无知识联系、无时间戳）
INSERT INTO note (user_id, title, content, note_type, node_type, parent_id, source_type, created_at, updated_at)
VALUES (@u, '罗尔定理', '## 定理内容\n\n- 条件：f(x) 在 [a,b] 上连续，在 (a,b) 内可导，且 f(a)=f(b)。\n- 结论：存在 ξ∈(a,b)，使得 f''(ξ)=0。\n\n## 几何意义\n\n两端等高的连续光滑曲线上，至少存在一点切线水平。\n\n## 使用注意\n\n三个条件缺一不可，解题前必须逐条验证，尤其是端点函数值相等。', 0, 0, @g_math2, 0, NOW(), NOW());

INSERT INTO note (user_id, title, content, note_type, node_type, parent_id, source_type, created_at, updated_at)
VALUES (@u, '拉格朗日中值定理', '## 定理内容\n\n去掉罗尔定理 f(a)=f(b) 的限制：f(b)-f(a)=f''(ξ)(b-a)，ξ∈(a,b)。\n\n## 与罗尔定理的关系\n\n构造辅助函数 F(x)=f(x)-f(a)-(f(b)-f(a))/(b-a)·(x-a)，对 F 使用罗尔定理即得。', 0, 0, @g_math2, 0, NOW(), NOW());

INSERT INTO note (user_id, title, content, note_type, node_type, parent_id, source_type, created_at, updated_at)
VALUES (@u, '矩阵的秩与线性方程组', '## 秩的定义\n\n矩阵中非零子式的最高阶数称为矩阵的秩，记作 r(A)。\n\n## 线性方程组解的判定\n\nr(A)=r(A|b) 且 r=n 时有唯一解；r<n 时有无穷多解；r(A)≠r(A|b) 时无解。', 0, 0, @g_linear, 0, NOW(), NOW());

-- 笔记：计算机科学（内容取自第 3 讲 AI 笔记，带蓝色时间戳；并添加网课知识联系）
INSERT INTO note (user_id, title, content, note_type, node_type, parent_id, source_type, course_id, created_at, updated_at)
VALUES (@u, '布尔逻辑与逻辑门',
CONCAT(SUBSTRING((SELECT content FROM note WHERE id = 3), 1, 900),
'NOT（非）：输入取反。[04:18]\n\n二进制只需要区分高低电平，抗干扰能力强，物理实现最可靠。[07:30]\n\n## 为什么这很重要\n\n逻辑门是构建一切计算的基础，就像用简单的开关搭建出复杂的判断。'),
0, 0, @g_cs, 0, 14, NOW(), NOW());
SET @n_bool = LAST_INSERT_ID();

-- 知识联系：网课（第 3 讲，跳转 04:18 = 258s）
INSERT INTO note_link (note_id, user_id, link_type, target_id, title, ts_sec, created_at, updated_at)
VALUES (@n_bool, @u, 'course', 14, '第 3 讲：布尔逻辑与逻辑门', 258, NOW(), NOW());
