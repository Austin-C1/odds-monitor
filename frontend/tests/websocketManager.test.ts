import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const { ticketMock } = vi.hoisted(() => ({
  ticketMock: vi.fn()
}))

vi.mock('../src/services/api', () => ({
  apiService: {
    auth: {
      getWebSocketTicket: ticketMock
    }
  }
}))

import { WebSocketManager } from '../src/services/websocket'

function buildJwt(expSeconds: number): string {
  const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url')
  const payload = Buffer.from(JSON.stringify({ exp: expSeconds })).toString('base64url')
  return `${header}.${payload}.signature`
}

class FakeWebSocket {
  static CONNECTING = 0
  static OPEN = 1
  static CLOSING = 2
  static CLOSED = 3
  static instances: FakeWebSocket[] = []

  readyState = FakeWebSocket.CONNECTING
  onopen: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  onclose: ((event: CloseEvent) => void) | null = null
  sentMessages: string[] = []

  constructor(readonly url: string) {
    FakeWebSocket.instances.push(this)
  }

  send(message: string) {
    this.sentMessages.push(message)
  }

  close() {
    this.readyState = FakeWebSocket.CLOSED
    this.onclose?.({} as CloseEvent)
  }

  open() {
    this.readyState = FakeWebSocket.OPEN
    this.onopen?.({} as Event)
  }
}

describe('WebSocketManager', () => {
  beforeEach(() => {
    ticketMock.mockReset()
    FakeWebSocket.instances = []
    vi.useFakeTimers()
    vi.stubGlobal('WebSocket', FakeWebSocket as unknown as typeof WebSocket)
    vi.stubGlobal('window', {
      location: {
        protocol: 'http:',
        host: '127.0.0.1:18880'
      }
    } as Window & typeof globalThis)
    vi.stubGlobal('localStorage', {
      getItem(key: string) {
        return key === 'jwt_token' ? 'jwt-token' : null
      }
    } as Storage)
  })

  afterEach(() => {
    vi.runOnlyPendingTimers()
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('allows reconnect after an explicit disconnect', async () => {
    ticketMock.mockResolvedValueOnce({
      data: { code: 0, data: { ticket: 'ticket-1' } }
    })

    const manager = new WebSocketManager()
    await manager.connect()
    expect(FakeWebSocket.instances).toHaveLength(1)

    FakeWebSocket.instances[0].open()
    manager.disconnect()

    ticketMock.mockResolvedValueOnce({
      data: { code: 0, data: { ticket: 'ticket-2' } }
    })

    await manager.connect()

    expect(FakeWebSocket.instances).toHaveLength(2)
    expect(FakeWebSocket.instances[1].url).toContain('ticket=ticket-2')
    manager.disconnect()
  })

  it('does not fall back to jwt in the websocket url when ticket creation fails', async () => {
    ticketMock.mockRejectedValueOnce(new Error('ticket failed'))

    const manager = new WebSocketManager()
    await manager.connect()

    expect(FakeWebSocket.instances).toHaveLength(0)
    manager.disconnect()
  })

  it('does not connect when the stored token is already expired', async () => {
    ticketMock.mockReset()
    vi.stubGlobal('localStorage', {
      getItem(key: string) {
        if (key !== 'jwt_token') {
          return null
        }
        return buildJwt(Math.floor(Date.now() / 1000) - 60)
      },
      setItem() {},
      removeItem() {}
    } as Storage)

    const manager = new WebSocketManager()
    await manager.connect()

    expect(ticketMock).not.toHaveBeenCalled()
    expect(FakeWebSocket.instances).toHaveLength(0)
    manager.disconnect()
  })
})
