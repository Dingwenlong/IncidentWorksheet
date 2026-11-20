import type { IncomingMessage, ServerResponse } from 'node:http'

export function createMockMiddleware(): (
  request: IncomingMessage,
  response: ServerResponse,
  next: () => void
) => Promise<void>
