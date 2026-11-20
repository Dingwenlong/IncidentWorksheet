import { defineConfig, loadEnv, type ViteDevServer } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(async ({ command, mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  if (command === 'serve' && mode === 'mock') {
    const { createMockMiddleware } = await import('./mock/middleware.mjs')
    return {
      plugins: [
        vue(),
        {
          name: 'workorder-api-mock',
          configureServer(server: ViteDevServer) {
            server.middlewares.use(createMockMiddleware())
          },
        },
      ],
      server: { host: '127.0.0.1', port: 5174, strictPort: true },
    }
  }
  return { plugins: [vue()], server: { proxy: { '/api': env.API_PROXY_TARGET || 'http://127.0.0.1:8080' } } }
})
