// 模型管理端到端冒烟：一次性账号 → 加配置（脱敏/重名） → 编辑保留 Key → 模块偏好 → 级联 → 注销物理删除
const BASE = 'http://127.0.0.1:9090'
const USERNAME = '__smoke_model_' + Date.now()

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

  // 添加配置：字段校验 + 正常添加 + 脱敏
  const missing = await api('POST', '/models', { token, body: { name: '坏配置', baseUrl: '', apiKey: 'sk-1', model: 'm' } })
  assert(missing.code !== 200, '缺字段被拒绝')
  const badUrl = await api('POST', '/models', { token, body: { name: '坏协议', baseUrl: 'ftp://x.com', apiKey: 'sk-1', model: 'm' } })
  assert(badUrl.code !== 200, '非 http(s) Base URL 被拒绝')
  const add = await api('POST', '/models', {
    token,
    body: { name: '智谱 GLM', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', apiKey: 'sk-1234567890abcd', model: 'glm-4-flash' },
  })
  assert(add.code === 200 && add.data.apiKeyMasked === 'sk-****abcd', '添加成功且 Key 脱敏返回（sk-****abcd）')

  // 同名拒绝
  const dup = await api('POST', '/models', {
    token,
    body: { name: '智谱 GLM', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', apiKey: 'sk-2', model: 'glm-4-flash' },
  })
  assert(dup.code !== 200, '同名配置被拒绝')

  // 编辑：Key 留空保持原值
  const upd = await api('PUT', `/models/${add.data.id}`, {
    token,
    body: { name: '智谱 GLM 新', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', apiKey: '', model: 'glm-4-plus' },
  })
  assert(upd.code === 200 && upd.data.name === '智谱 GLM 新' && upd.data.model === 'glm-4-plus', '编辑生效')

  // 测试连接（假 Key → 连接失败，返回业务错误而非 500）
  const test = await api('POST', `/models/${add.data.id}/test`, { token })
  assert(test.code !== 200, '假 Key 连接测试失败（返回业务错误）')

  // 模块偏好：设置 → 生效 → 指向已删配置回退 → 系统默认
  const setPref = await api('PUT', '/models/module-preferences?module=chat&configId=' + add.data.id, { token })
  assert(setPref.code === 200, '对话模块偏好设置成功')
  const prefs = await api('GET', '/models/module-preferences', { token })
  assert(prefs.code === 200 && prefs.data.chat === add.data.id, '模块偏好读取正确')
  assert(prefs.data.briefing === null && prefs.data.course_note === null, '未设置模块为 null（系统默认）')
  const badModule = await api('PUT', '/models/module-preferences?module=hacker&configId=' + add.data.id, { token })
  assert(badModule.code !== 200, '非法模块被拒绝')

  // 删除配置 → 模块偏好级联清除（自动回退系统默认）
  const del = await api('DELETE', `/models/${add.data.id}`, { token })
  assert(del.code === 200, '删除配置成功')
  const prefsAfter = await api('GET', '/models/module-preferences', { token })
  assert(prefsAfter.code === 200 && prefsAfter.data.chat === null, '删除后对话模块回退系统默认')

  // 注销：模型配置物理删除
  const add2 = await api('POST', '/models', {
    token,
    body: { name: '注销前配置', baseUrl: 'https://api.example.com', apiKey: 'sk-secret-9876', model: 'm-1' },
  })
  assert(add2.code === 200, '注销前添加配置成功')
  const delAccount = await api('POST', '/users/me/delete', { token, body: { password: 'pass123456' } })
  assert(delAccount.code === 200, '注销成功')
  const relogin = await api('POST', '/auth/login', { body: { username: USERNAME, password: 'pass123456' } })
  assert(relogin.code !== 200, '已注销账号登录被拒（配置随注销清除）')

  console.log('\n全部冒烟断言通过')
}

main().catch((e) => {
  console.error('SMOKE FAILED:', e.message)
  process.exit(1)
})
