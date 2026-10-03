// 复习系统端到端冒烟：一次性账号 → 建笔记 → 加卡 → 今日队列 → 评分调度 → 统计 → 删除级联
const BASE = 'http://127.0.0.1:9090'
const USERNAME = '__smoke_review_' + Date.now()

function assert(cond, msg) {
  if (!cond) {
    throw new Error('断言失败: ' + msg)
  }
  console.log('✓ ' + msg)
}

async function api(method, path, { token, body } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { 'sa-token': token } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return res.json()
}

const main = async () => {
  const reg = await api('POST', '/auth/register', { body: { username: USERNAME, password: 'pass123456' } })
  assert(reg.code === 200, '注册成功')
  const login = await api('POST', '/auth/login', { body: { username: USERNAME, password: 'pass123456' } })
  assert(login.code === 200, '登录成功')
  const token = login.data.tokenValue

  // 建一篇笔记作为卡片来源
  const note = await api('POST', '/notes/note', { token, body: { title: '复习系统冒烟笔记' } })
  assert(note.code === 200 && note.data > 0, '笔记创建成功')
  const noteId = note.data

  // 加卡：分组不可加 / 非法类型拒绝 / 正常加入
  const group = await api('POST', '/notes/group', { token, body: { name: '分组' } })
  const groupAdd = await api('POST', '/review/cards', { token, body: { cardType: 'note', refId: group.data } })
  assert(groupAdd.code !== 200, '分组不能加入复习')
  const badType = await api('POST', '/review/cards', { token, body: { cardType: 'video', refId: noteId } })
  assert(badType.code !== 200, '非法卡片类型被拒绝')
  const add = await api('POST', '/review/cards', { token, body: { cardType: 'note', refId: noteId } })
  assert(add.code === 200 && add.data.id > 0, '笔记卡加入成功（due_at = 现在）')
  const cardId = add.data.id

  // 去重：重复加入被拒
  const dup = await api('POST', '/review/cards', { token, body: { cardType: 'note', refId: noteId } })
  assert(dup.code !== 200, '重复加入被拒绝')

  // 状态查询
  const status = await api('GET', `/review/status?cardType=note&refId=${noteId}`, { token })
  assert(status.code === 200 && status.data === true, '状态查询：已在队列')

  // 今日队列：卡片两面组装正确
  const queue = await api('GET', '/review/today', { token })
  assert(queue.code === 200 && queue.data.length === 1, '今日队列含 1 张卡')
  assert(queue.data[0].frontText === '复习系统冒烟笔记', '正面 = 笔记标题')

  // 评分：非法评分拒绝；熟练 → 间隔 = round(1 × 2.5) = 3 天，到期时间后移
  const badGrade = await api('POST', `/review/cards/${cardId}/review`, { token, body: { grade: 9 } })
  assert(badGrade.code !== 200, '非法评分被拒绝')
  const good = await api('POST', `/review/cards/${cardId}/review`, { token, body: { grade: 2 } })
  assert(good.code === 200 && good.data.intervalDays === 3, '熟练评分：间隔推进到 3 天')
  const again = await api('POST', `/review/cards/${cardId}/review`, { token, body: { grade: 0 } })
  assert(again.code === 200 && again.data.intervalDays === 1 && again.data.lapses === 1, '生疏评分：间隔重置 1 天 + 生疏计数')

  // 统计：已复习 2 次；卡未到期（下次明天）→ dueCount 0
  const stats = await api('GET', '/review/stats', { token })
  assert(stats.code === 200 && stats.data.reviewedToday === 2, '统计：今日已复习 2 次')
  assert(stats.data.dueCount === 0, '统计：评分后无到期卡（明天再来）')

  // 删除级联：删除笔记 → 复习卡自动移出
  const del = await api('DELETE', `/notes/${noteId}`, { token })
  assert(del.code === 200, '笔记删除成功')
  const statusAfter = await api('GET', `/review/status?cardType=note&refId=${noteId}`, { token })
  assert(statusAfter.code === 200 && statusAfter.data === false, '级联：笔记删除后复习卡自动移出')

  // 移出与恢复：删除来源后不可再加卡（来源不存在）；对仍存在的来源，移出后重新加入 → 恢复
  const add2 = await api('POST', '/review/cards', { token, body: { cardType: 'note', refId: noteId } })
  assert(add2.code !== 200, '来源已删除的笔记不可再加入复习')
  const note2 = await api('POST', '/notes/note', { token, body: { title: '第二篇笔记' } })
  assert(note2.code === 200, '第二篇笔记创建成功')
  const add3 = await api('POST', '/review/cards', { token, body: { cardType: 'note', refId: note2.data } })
  assert(add3.code === 200, '第二张卡加入成功')
  const rm = await api('DELETE', `/review/cards/${add3.data.id}`, { token })
  assert(rm.code === 200, '移出复习队列成功')
  const reAdd = await api('POST', '/review/cards', { token, body: { cardType: 'note', refId: note2.data } })
  assert(reAdd.code === 200 && reAdd.data.intervalDays === 0 && reAdd.data.reps === 0, '移出后重新加入 → 恢复并重置调度状态')

  console.log('\n全部冒烟断言通过')
}

main().catch((e) => {
  console.error('SMOKE FAILED:', e.message)
  process.exit(1)
})
