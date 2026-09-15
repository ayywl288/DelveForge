import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  // 第三个参数传空字符串表示读取全部环境变量，而不只是 VITE_ 前缀的变量。
  // 代理目标属于 dev server 配置，不需要注入到浏览器端产物里。
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [vue()],
    server: {
      proxy: {
        // 前端代码始终使用相对路径 /api/...，由 dev server 代理到本地 Backend。
        // 这样前端不持有 Backend 地址，Backend 也不需要为开发环境开放 CORS。
        '/api': {
          target: env.BACKEND_DEV_URL ?? 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
  }
})
