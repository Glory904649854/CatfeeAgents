import { z } from 'zod'

const eventSchema = z.object({
  id: z.string().max(80).optional(),
  taskId: z.string().max(80).nullable().optional(),
  runId: z.string().max(80).nullable().optional(),
  sequence: z.number().int().nullable().optional(),
  type: z.string().min(1).max(80),
  level: z.enum(['debug', 'info', 'warn', 'error', 'security']).optional(),
  payload: z.record(z.string(), z.unknown()).optional(),
  occurredAt: z.string().max(40).optional()
})

const schema = z.object({
  events: z.array(eventSchema).min(1).max(100)
})

/** HTTP 补传通道：WebSocket 不可用或事件积压时使用。 */
export default defineEventHandler(async (event) => {
  const device = authenticateDeviceHeaders(event)
  const parsed = schema.safeParse(await readBody(event))
  if (!parsed.success) throw badRequest('事件格式不合法')

  let accepted = 0
  for (const item of parsed.data.events) {
    if (insertAgentEvent(device.id, item)) accepted += 1
  }
  touchDevice(device.id)

  return { accepted }
})
