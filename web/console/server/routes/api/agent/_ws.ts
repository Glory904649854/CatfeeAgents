import type { AgentPeer } from '../../../utils/agent-hub'

/**
 * Android Agent 长连接入口：ws://<host>/api/agent/_ws
 *
 * 首条消息必须是 hello：
 * { type: "hello", deviceId, secret, agentVersion, androidVersion, model, capabilities }
 */
export default defineWebSocketHandler({
  message(peer, message) {
    handleAgentMessage(peer as unknown as AgentPeer, message.text())
  },
  close(peer) {
    detachAgent(peer as unknown as AgentPeer)
  },
  error(peer, error) {
    console.warn('[catfee] agent websocket error', error)
    detachAgent(peer as unknown as AgentPeer)
  }
})
