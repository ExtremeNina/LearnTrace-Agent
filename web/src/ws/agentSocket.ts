import type { ClientMessage, ServerMessage } from '../types/ws'

/**
 * /ws/agent 封装：连接、自动重连（指数退避）、事件分发。
 * token 走 query 参数（浏览器 WS API 不支持自定义请求头）。
 */
type EventListener = (msg: ServerMessage) => void

let socket: WebSocket | null = null
let listener: EventListener | null = null
let lastToken = ''
let attempts = 0
let reconnectTimer: number | null = null
/** 连接建立中 / 重连中暂存的上行消息，OPEN 后按序冲刷（跨页发起的种子消息依赖此保证） */
let outbox: ClientMessage[] = []

export function connect(token: string, onEvent: EventListener) {
  listener = onEvent
  lastToken = token
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
    return
  }
  open()
}

function open() {
  socket = new WebSocket(`ws://localhost:9090/ws/agent?token=${encodeURIComponent(lastToken)}`)
  socket.onopen = () => {
    attempts = 0
    if (outbox.length > 0) {
      const pending = outbox
      outbox = []
      for (const msg of pending) {
        socket?.send(JSON.stringify(msg))
      }
    }
  }
  socket.onmessage = (e) => {
    if (listener) {
      listener(JSON.parse(e.data as string) as ServerMessage)
    }
  }
  socket.onclose = () => {
    scheduleReconnect()
  }
  socket.onerror = () => {
    socket?.close()
  }
}

function scheduleReconnect() {
  if (reconnectTimer !== null) {
    return
  }
  attempts += 1
  const delay = Math.min(1000 * 2 ** attempts, 15000)
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = null
    open()
  }, delay)
}

export function sendMessage(msg: ClientMessage) {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(msg))
    return
  }
  outbox.push(msg)
}

export function close() {
  if (reconnectTimer !== null) {
    window.clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  listener = null
  socket?.close()
  socket = null
}
