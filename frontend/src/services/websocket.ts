import { getToken } from '../utils/auth'
import { apiService } from './api'
import { registerAuthExpiredHandler } from './authSession'

/**
 * WebSocket 消息类型（int 值）
 */
export enum WebSocketMessageType {
  SUB = 1,        // 订阅
  UNSUB = 2,      // 取消订阅
  DATA = 3,       // 数据推送
  SUB_ACK = 4,    // 订阅确认
  PING = 5,       // 心跳
  PONG = 6        // 心跳响应
}

/**
 * WebSocket 消息
 */
export interface WebSocketMessage {
  type: number  // WebSocketMessageType 的 int 值（1:SUB, 2:UNSUB, 3:DATA, 4:SUB_ACK, 5:PING, 6:PONG）
  channel?: string
  payload?: any
  timestamp?: number
  status?: number  // 0: success, 非0: error
  message?: string
}

/**
 * 订阅回调函数
 */
export type SubscriptionCallback = (data: any) => void

/**
 * 全局 WebSocket 管理器
 */
export class WebSocketManager {
  private ws: WebSocket | null = null
  private reconnectTimer: NodeJS.Timeout | null = null
  private pingInterval: NodeJS.Timeout | null = null
  private isConnecting = false
  private isUnmounting = false
  
  // 订阅管理：channel -> Set<callback>
  private subscriptions = new Map<string, Set<SubscriptionCallback>>()
  
  // 订阅状态：channel -> boolean（是否已向后端订阅）
  private subscribedChannels = new Set<string>()
  
  // 连接状态回调
  private connectionCallbacks: Set<(connected: boolean) => void> = new Set()
  
  private reconnectDelay = 3000
  private pingIntervalTime = 30000
  
  /**
   * 连接 WebSocket（全局共享连接）
   * 使用短期票据认证，避免在 URL 中暴露 JWT
   */
  async connect(): Promise<void> {
    this.isUnmounting = false

    // 检查是否有token，未登录不允许连接
    const token = getToken()
    if (!token) {
      return
    }

    // 如果已经连接或正在连接，直接返回
    if (this.ws?.readyState === WebSocket.OPEN || this.isConnecting) {
      return
    }

    // 如果正在卸载，不允许连接
    if (this.isUnmounting) {
      return
    }

    this.isConnecting = true

    try {
      // 获取短期票据
      const wsUrl = await this.getWebSocketUrl()

      // 如果已经有连接（但状态不是 OPEN），先关闭
      if (this.ws) {
        try {
          this.ws.close()
        } catch (e) {
          // 忽略关闭错误
        }
        this.ws = null
      }

      const ws = new WebSocket(wsUrl)
      this.ws = ws

      ws.onopen = () => {
        this.isConnecting = false
        this.notifyConnectionStatus(true)
        this.startPing()
        this.resubscribeAll()  // 重新订阅所有频道
      }

      ws.onmessage = (event) => {
        this.handleMessage(event.data)
      }

      ws.onerror = () => {
        this.isConnecting = false
        this.notifyConnectionStatus(false)
      }

      ws.onclose = () => {
        this.isConnecting = false
        this.notifyConnectionStatus(false)
        this.stopPing()
        // 自动重连（除非正在卸载或未登录）
        if (!this.isUnmounting && getToken()) {
          this.scheduleReconnect()
        }
      }
    } catch {
      this.isConnecting = false
      this.notifyConnectionStatus(false)
      // 自动重连（除非正在卸载或未登录）
      if (!this.isUnmounting && getToken()) {
        this.scheduleReconnect()
      }
    }
  }
  
  /**
   * 断开连接（仅在应用完全卸载时调用）
   */
  disconnect(): void {
    this.isUnmounting = true
    this.isConnecting = false
    this.stopPing()
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    if (this.ws) {
      try {
        this.ws.close()
      } catch (e) {
        // 忽略关闭错误
      }
      this.ws = null
    }
    this.notifyConnectionStatus(false)
  }
  
  /**
   * 订阅频道
   */
  subscribe(channel: string, callback: SubscriptionCallback, payload?: any): () => void {
    // 添加订阅者
    if (!this.subscriptions.has(channel)) {
      this.subscriptions.set(channel, new Set())
    }
    this.subscriptions.get(channel)!.add(callback)
    
    // 如果还未向后端订阅，发送订阅消息
    if (!this.subscribedChannels.has(channel)) {
      this.sendSubscribe(channel, payload)
    }
    
    // 返回取消订阅函数
    return () => {
      this.unsubscribe(channel, callback)
    }
  }
  
  /**
   * 取消订阅
   */
  unsubscribe(channel: string, callback: SubscriptionCallback): void {
    const callbacks = this.subscriptions.get(channel)
    if (callbacks) {
      callbacks.delete(callback)
      
      // 如果没有订阅者了，向后端取消订阅
      if (callbacks.size === 0) {
        this.subscriptions.delete(channel)
        this.sendUnsubscribe(channel)
        this.subscribedChannels.delete(channel)
      }
    }
  }
  
  /**
   * 发送订阅消息
   */
  private sendSubscribe(channel: string, payload?: any): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      const message: WebSocketMessage = {
        type: WebSocketMessageType.SUB,
        channel,
        payload
      }
      this.ws.send(JSON.stringify(message))
      this.subscribedChannels.add(channel)
    } else {
      // 如果连接未建立，先连接
      this.connect()
      // 连接建立后会通过 resubscribeAll 自动订阅
    }
  }
  
  /**
   * 发送取消订阅消息
   */
  private sendUnsubscribe(channel: string): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      const message: WebSocketMessage = {
        type: WebSocketMessageType.UNSUB,
        channel
      }
      this.ws.send(JSON.stringify(message))
    }
  }
  
  /**
   * 处理收到的消息
   */
  private handleMessage(data: string): void {
    // 处理心跳
    if (data === 'PONG') {
      return
    }
    
    try {
      const message: WebSocketMessage = JSON.parse(data)
      
      if (message.type === WebSocketMessageType.DATA && message.channel) {
        // 数据推送：分发到订阅者
        const callbacks = this.subscriptions.get(message.channel)
        if (callbacks) {
          callbacks.forEach(callback => {
            try {
              callback(message.payload)
            } catch {
              // ignore subscriber callback failures to keep the shared socket alive
            }
          })
        }
      } else if (message.type === WebSocketMessageType.SUB_ACK) {
        // 订阅确认
        if (message.status !== undefined && message.status !== 0) {
          this.subscribedChannels.delete(message.channel || '')
        }
      }
    } catch {
      // ignore malformed messages
    }
  }
  
  /**
   * 重新订阅所有频道
   */
  private resubscribeAll(): void {
    this.subscribedChannels.clear()
    this.subscriptions.forEach((callbacks, channel) => {
      if (callbacks.size > 0) {
        this.sendSubscribe(channel)
      }
    })
  }
  
  /**
   * 安排重连
   */
  private scheduleReconnect(): void {
    if (this.isUnmounting) {
      return
    }
    
    // 检查是否有token，未登录不重连
    if (!getToken()) {
      return
    }
    
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
    }
    
    this.reconnectTimer = setTimeout(() => {
      this.connect()
    }, this.reconnectDelay)
  }
  
  /**
   * 开始心跳
   */
  private startPing(): void {
    this.stopPing()
    
    // 立即发送一次心跳
    const sendPing = () => {
      if (this.ws?.readyState === WebSocket.OPEN) {
        this.ws.send('PING')
      }
    }
    
    sendPing()
    
    // 每30秒发送一次心跳
    this.pingInterval = setInterval(sendPing, this.pingIntervalTime)
  }
  
  /**
   * 停止心跳
   */
  private stopPing(): void {
    if (this.pingInterval) {
      clearInterval(this.pingInterval)
      this.pingInterval = null
    }
  }
  
  /**
   * 获取 WebSocket URL（使用短期票据认证）
   * 默认使用相对路径 /ws（通过反向代理转发）
   * 如果设置了 VITE_WS_URL 环境变量，则使用完整 URL（用于跨域场景）
   */
  private async getWebSocketUrl(): Promise<string> {
    const envWsUrl = import.meta.env.VITE_WS_URL
    let wsBaseUrl: string

    if (envWsUrl) {
      // 如果设置了环境变量，使用完整 URL（支持跨域）
      wsBaseUrl = envWsUrl
    } else {
      // 否则使用相对路径（通过反向代理转发）
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      const host = window.location.host
      wsBaseUrl = `${protocol}//${host}`
    }

    // 获取短期票据（避免在 URL 中暴露 JWT）
    // 使用动态导入避免循环依赖
    try {
      const response = await apiService.auth.getWebSocketTicket()
      if (response.data.code === 0 && response.data.data?.ticket) {
        return `${wsBaseUrl}/ws?ticket=${encodeURIComponent(response.data.data.ticket)}`
      }
      throw new Error('WebSocket ticket missing from response')
    } catch {
      throw new Error('Failed to get WebSocket ticket')
    }
  }
  
  /**
   * 注册连接状态回调
   */
  onConnectionChange(callback: (connected: boolean) => void): () => void {
    this.connectionCallbacks.add(callback)
    return () => {
      this.connectionCallbacks.delete(callback)
    }
  }
  
  /**
   * 通知连接状态变化
   */
  private notifyConnectionStatus(connected: boolean): void {
    this.connectionCallbacks.forEach(callback => {
      try {
        callback(connected)
      } catch {
        // ignore listener failures
      }
    })
  }
  
  /**
   * 获取连接状态
   */
  isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN
  }
}

// 导出单例
export const wsManager = new WebSocketManager()

registerAuthExpiredHandler(() => {
  wsManager.disconnect()
})

