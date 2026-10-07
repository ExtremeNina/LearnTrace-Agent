// video_6（45 分钟线性代数）全链路观察：视频管理直传 → 流水线各阶段 → 最终产物打印
const BASE = 'http://127.0.0.1:9090'
const USERNAME = '__run_video6_' + Date.now()
const VIDEO = process.argv[2]

const t0 = Date.now()
const elapsed = () => {
  const s = Math.floor((Date.now() - t0) / 1000)
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}
const fmtTs = (sec) => sec == null ? '--' : `[${String(Math.floor(sec / 60)).padStart(2, '0')}:${String(sec % 60).padStart(2, '0')}]`

function assert(cond, msg) {
  if (!cond) { throw new Error('断言失败: ' + msg) }
  console.log(`[${elapsed()}] ✓ ${msg}`)
}

async function api(method, path, { token, body, form } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      ...(form ? {} : { 'Content-Type': 'application/json' }),
      ...(token ? { 'sa-token': token } : {}),
    },
    body: form ?? (body === undefined ? undefined : JSON.stringify(body)),
  })
  return res.json()
}

const main = async () => {
  console.log(`[${elapsed()}] === 1. 注册 / 登录 / 画像 ===`)
  await api('POST', '/auth/register', { body: { username: USERNAME, password: 'pass123456' } })
  const login = await api('POST', '/auth/login', { body: { username: USERNAME, password: 'pass123456' } })
  const token = login.data.tokenValue
  console.log(`[${elapsed()}] 账号: ${USERNAME}`)

  await api('PUT', '/profile', { token, body: {
    gradeLevel: '大学',
    level: '数学基础比较薄弱',
    goal: '为了通过期末考试',
    note: '希望通俗易懂地讲这门课',
  } })
  const prof = await api('GET', '/profile', { token })
  assert(prof.data && prof.data.goal === '为了通过期末考试', '画像已设置（大学 / 数学基础薄弱 / 通过期末考试 / 通俗讲解）')

  console.log(`\n[${elapsed()}] === 2. 视频管理直传（45min > 30min 对话上限，走课程链路） ===`)
  const form = new FormData()
  form.append('file', new Blob([readFileSync(VIDEO)], { type: 'video/mp4' }), 'video.mp4')
  form.append('title', '45分钟线性代数通俗讲解')
  form.append('subject', '数学')
  const upload = await api('POST', '/courses', { token, form })
  assert(upload.code === 200, `课程已创建（id=${upload.data.id}，status=${upload.data.status}）`)
  const courseId = upload.data.id

  console.log(`\n[${elapsed()}] === 3. 流水线阶段轮询（stage 变化即打印） ===`)
  const STAGE_TEXT = {
    PENDING: '排队等待处理',
    UPLOADING: '正在上传视频到云存储',
    EXTRACTING: '正在提取音频与关键帧',
    TRANSCRIBING: '正在转写语音（ASR 分片）',
    ANALYZING: '正在识别画面关键帧（OCR）',
    UNDERSTANDING: '正在进行内容理解',
    REVIEWING: '正在多角色评审',
    NOTE_GENERATING: '正在生成 AI 笔记',
    COMPLETED: '处理完成',
    FAILED: '处理失败',
  }
  let lastStage = ''
  const stageTimes = {}
  const detail = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('流水线超时（25 分钟）')), 25 * 60 * 1000)
    const poll = setInterval(async () => {
      try {
        const d = await api('GET', `/courses/${courseId}`, { token })
        const c = d.data.course
        if (c.stage !== lastStage) {
          lastStage = c.stage
          stageTimes[c.stage] = elapsed()
          console.log(`[${elapsed()}] [COURSE] ${c.status} / ${c.stage}: ${STAGE_TEXT[c.stage] ?? ''}`)
        }
        if (c.status === 'SUCCESS' || c.status === 'FAILED') {
          clearInterval(poll); clearTimeout(timer); resolve(d.data)
        }
      } catch (e) { /* 轮询容错 */ }
    }, 5000)
  })
  const c = detail.course
  assert(c.status === 'SUCCESS', `课程处理完成（总耗时 ${elapsed()}）`)
  console.log(`[${elapsed()}] 阶段耗时表: ` + Object.entries(stageTimes).map(([s, t]) => `${s}@${t}`).join(' → '))
  if (c.errorMsg) {
    console.log(`[${elapsed()}] ⚠ course.errorMsg: ${c.errorMsg}`)
  } else {
    console.log(`[${elapsed()}] ✓ course.errorMsg 为空（质检与评审全部通过）`)
  }

  console.log(`\n[${elapsed()}] === 4. 转写与修正统计 ===`)
  const segs = detail.transcript ?? []
  const corrected = segs.filter((s) => s.textCorrected)
  const totalChars = segs.reduce((n, s) => n + (s.textCorrected ?? s.text ?? '').length, 0)
  console.log(`  转写分段: ${segs.length} 段，总字数 ${totalChars}，修正版应用 ${corrected.length} 段`)
  if (segs.length) {
    console.log(`  首段: ${fmtTs(segs[0].startSec)} ${(segs[0].textCorrected ?? segs[0].text ?? '').slice(0, 40)}…`)
    console.log(`  末段: ${fmtTs(segs[segs.length - 1].startSec)} ${(segs[segs.length - 1].textCorrected ?? segs[segs.length - 1].text ?? '').slice(0, 40)}…`)
  }
  console.log(`  关键帧: ${(detail.frames ?? []).length} 帧`)

  console.log(`\n[${elapsed()}] === 5. ContentDocument（内容理解产物） ===`)
  const doc = detail.document
  assert(doc && doc.id, 'ContentDocument 已生成')
  console.log(`  标题: ${doc.title}`)
  console.log(`  摘要: ${doc.summary ?? ''}`)
  const sections = detail.sections ?? []
  console.log(`  章节（${sections.length} 个）:`)
  for (const s of sections) {
    console.log(`   ${fmtTs(s.startSec)}-${fmtTs(s.endSec)} ${s.title}${s.summary ? ' — ' + s.summary.slice(0, 50) : ''}`)
  }
  const points = detail.knowledgePoints ?? []
  console.log(`  知识点（${points.length} 个）:`)
  for (const p of points) {
    const marks = (p.important === 1 ? ' [重点]' : '') + (p.errorProne === 1 ? ' [易错]' : '')
    console.log(`   ${fmtTs(p.timeSec)} ${p.name}${marks}`)
  }

  console.log(`\n[${elapsed()}] === 6. AI 笔记（Renderer 从 ContentDocument 渲染，全文如下） ===`)
  const note = detail.note
  assert(note && note.content.length > 200, `AI 笔记已生成（${note.content.length} 字）`)
  console.log('─'.repeat(60))
  console.log(note.content)
  console.log('─'.repeat(60))

  console.log(`\n[${elapsed()}] === 7. 练习闭环抽查（QuizAgent 出题 → 练习抽题） ===`)
  // 出题（WS 对话）
  const conv = await api('POST', '/conversations', { token, body: {} })
  const ws = new WebSocket(BASE.replace('http', 'ws') + '/ws/agent?token=' + token)
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej })
  let text = ''
  ws.send(JSON.stringify({ type: 'chat.send', conversationId: conv.data.id, content: '帮我从这门课出 5 道练习题' }))
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('出题回合超时')), 300 * 1000)
    ws.onmessage = (e) => {
      const m = JSON.parse(e.data)
      if (m.type === 'DELTA' && m.text) { text += m.text }
      if (m.type === 'STOP' || m.type === 'ERROR') { clearTimeout(timer); resolve() }
    }
  })
  console.log(`  出题回复（前 400 字）:\n${text.slice(0, 400).replace(/\n/g, '\n  ')}`)
  const pick = await api('GET', '/quiz/pick?count=10&range=all', { token })
  assert(pick.code === 200 && pick.data.length >= 5, `练习抽题返回 ${pick.data?.length ?? 0} 题（新题已入练习池）`)
  console.log('  抽到样例: ' + (pick.data[0]?.questionText ?? '').slice(0, 50))
  ws.close()
  console.log(`\n[${elapsed()}] === video_6 全链路观察结束 ===`)
}

import { readFileSync } from 'node:fs'
main().catch((e) => { console.error(`[${elapsed()}] 观察失败:`, e.message); process.exit(1) })
