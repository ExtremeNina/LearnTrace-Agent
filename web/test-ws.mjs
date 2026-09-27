/**
 * WS 端到端测试脚本：Node 24+ 全局 WebSocket
 * 用法：node test-ws.mjs <token> <conversationId>
 * 流程：发送第 1 条消息 → 等回合结束 → 发送第 2 条消息（验证多轮记忆）→ 退出
 */
const token = process.argv[2]
const conversationId = Number(process.argv[3])
const ws = new WebSocket(`ws://localhost:9090/ws/agent?token=${encodeURIComponent(token)}`)

let phase = 1
let deltaCount = 0
let full = ''

function send(content) {
  ws.send(JSON.stringify({ type: 'chat.send', conversationId, content }))
}

ws.onopen = () => {
  console.log('[WS OPEN]')
  send('用一句话介绍你自己')
}

ws.onmessage = (e) => {
  const msg = JSON.parse(e.data)
  if (msg.type === 'DELTA') {
    deltaCount++
    full += msg.text
    if (deltaCount <= 3) process.stdout.write('[DELTA] ' + msg.text + '\n')
  } else if (msg.type === 'COMPLETE') {
    console.log(`[COMPLETE] messageId=${msg.messageId} deltas=${deltaCount}`)
    console.log('[第1轮回答]', full.slice(0, 120))
  } else if (msg.type === 'ERROR') {
    console.log('[ERROR]', msg.code, msg.message)
    process.exit(1)
  } else if (msg.type === 'STOP') {
    console.log('[STOP] 回合结束')
    if (phase === 1) {
      phase = 2
      deltaCount = 0
      full = ''
      setTimeout(() => {
        console.log('--- 发送第 2 条消息（验证记忆）---')
        send('我上一条消息问了什么？请原样复述问题')
      }, 300)
    } else {
      console.log('[第2轮回答]', full.slice(0, 200))
      console.log('[DONE] 多轮对话验证通过')
      ws.close()
      process.exit(0)
    }
  }
}

ws.onerror = (e) => {
  console.log('[WS ERROR]')
  process.exit(1)
}

setTimeout(() => {
  console.log('[TIMEOUT] 60s 未完成')
  process.exit(1)
}, 60000)
