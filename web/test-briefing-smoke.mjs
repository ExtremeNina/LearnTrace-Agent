// 每日简报端到端冒烟：一次性账号 → 建笔记/错题卡 → 惰性生成简报（真实 LLM）→ 当天缓存幂等 → 强制刷新
const BASE = 'http://127.0.0.1:9090'
const USERNAME = '__smoke_brief_' + Date.now()

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
  await api('POST', '/auth/register', { body: { username: USERNAME, password: 'pass123456' } })
  const login = await api('POST', '/auth/login', { body: { username: USERNAME, password: 'pass123456' } })
  assert(login.code === 200, '登录成功')
  const token = login.data.tokenValue

  // 造数据：一篇笔记加入复习并评一次"生疏"（制造薄弱卡）
  const note = await api('POST', '/notes/note', { token, body: { title: '简报冒烟笔记：极限计算' } })
  const noteId = note.data
  const add = await api('POST', '/review/cards', { token, body: { cardType: 'note', refId: noteId } })
  const cardId = add.data.id
  await api('POST', `/review/cards/${cardId}/review`, { token, body: { grade: 0 } })
  console.log('✓ 测试数据就绪（笔记卡 + 一次生疏评分）')

  // 首次获取简报：触发惰性生成（真实 LLM 调用）
  const t0 = Date.now()
  const brief1 = await api('GET', '/briefing/today', { token })
  const elapsed = ((Date.now() - t0) / 1000).toFixed(1)
  assert(brief1.code === 200, '今日简报生成成功')
  assert(brief1.data.content && brief1.data.content.length > 20, `简报正文非空（${brief1.data.content.length} 字，耗时 ${elapsed}s）`)
  assert(brief1.data.stats && brief1.data.stats.weakCards !== undefined, '统计快照携带 weakCards')

  // 当天第二次获取：读缓存（不再调用 LLM，毫秒级）
  const t1 = Date.now()
  const brief2 = await api('GET', '/briefing/today', { token })
  const elapsed2 = ((Date.now() - t1) / 1000).toFixed(2)
  assert(brief2.code === 200 && brief2.data.content === brief1.data.content, `当天缓存幂等（耗时 ${elapsed2}s，内容一致）`)

  // 强制刷新：重新生成
  const refreshed = await api('POST', '/briefing/refresh', { token })
  assert(refreshed.code === 200 && refreshed.data.content, '强制刷新重新生成成功')

  // 注销清理
  await api('POST', '/users/me/delete', { token, body: { password: 'pass123456' } })
  console.log('✓ 一次性账号已注销清理')

  console.log('\n全部冒烟断言通过')
}

main().catch((e) => {
  console.error('SMOKE FAILED:', e.message)
  process.exit(1)
})
