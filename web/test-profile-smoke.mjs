// 个人页面端到端冒烟：一次性账号 → 资料 → 偏好 → 改密 → 重登 → 注销 → 复登拒绝
const BASE = 'http://127.0.0.1:9090'
const USERNAME = '__smoke_profile_' + Date.now()

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
  const json = await res.json()
  return json
}

const main = async () => {
  // 注册 + 登录
  const reg = await api('POST', '/auth/register', { body: { username: USERNAME, password: 'pass123456' } })
  assert(reg.code === 200, '注册成功')
  const login1 = await api('POST', '/auth/login', { body: { username: USERNAME, password: 'pass123456' } })
  assert(login1.code === 200 && login1.data.tokenValue, '登录成功并取得令牌')
  const token = login1.data.tokenValue

  // 资料：默认值 + 编辑
  let me = await api('GET', '/users/me', { token })
  assert(me.code === 200 && me.data.theme === 'LIGHT' && me.data.notifyTaskEnabled === true, '默认偏好：浅色 + 通知开')
  me = await api('PUT', '/users/me', {
    token,
    body: { nickname: '冒烟测试员', email: 'smoke@test.com', bio: '测试简介', avatarUrl: 'https://oss.example.com/av.png' },
  })
  assert(me.code === 200 && me.data.nickname === '冒烟测试员' && me.data.bio === '测试简介', '资料编辑生效')

  // 空昵称拒绝
  const badNick = await api('PUT', '/users/me', { token, body: { nickname: '   ' } })
  assert(badNick.code !== 200, '空昵称被拒绝')

  // 偏好：深色 + 通知关；非法主题拒绝
  const prefs = await api('PUT', '/users/me/preferences', { token, body: { theme: 'DARK', notifyTaskEnabled: false } })
  assert(prefs.code === 200 && prefs.data.theme === 'DARK' && prefs.data.notifyTaskEnabled === false, '偏好切换生效（深色 + 通知关）')
  const badTheme = await api('PUT', '/users/me/preferences', { token, body: { theme: 'BLUE' } })
  assert(badTheme.code !== 200, '非法主题被拒绝')

  // 改密：旧密码错误拒绝 → 正确修改 → 新密码可登录
  const wrongOld = await api('PUT', '/users/me/password', { token, body: { oldPassword: 'wrong', newPassword: 'new654321' } })
  assert(wrongOld.code !== 200, '原密码错误被拒绝')
  const chg = await api('PUT', '/users/me/password', { token, body: { oldPassword: 'pass123456', newPassword: 'new654321' } })
  assert(chg.code === 200, '修改密码成功')
  const loginNew = await api('POST', '/auth/login', { body: { username: USERNAME, password: 'new654321' } })
  assert(loginNew.code === 200, '新密码可登录')

  // 注销：密码确认 → 逻辑删除 → 登出 → 原令牌失效 → 登录被拒
  const delWrong = await api('POST', '/users/me/delete', { token, body: { password: 'wrong' } })
  assert(delWrong.code !== 200, '注销密码错误被拒绝')
  const del = await api('POST', '/users/me/delete', { token, body: { password: 'new654321' } })
  assert(del.code === 200, '注销成功')
  const meAfter = await api('GET', '/users/me', { token })
  assert(meAfter.code !== 200, '注销后原令牌失效')
  const loginDeleted = await api('POST', '/auth/login', { body: { username: USERNAME, password: 'new654321' } })
  assert(loginDeleted.code !== 200 && String(loginDeleted.message || '').includes('注销'), '已注销账号登录被拒绝')

  console.log('\n全部冒烟断言通过')
}

main().catch((e) => {
  console.error('SMOKE FAILED:', e.message)
  process.exit(1)
})
