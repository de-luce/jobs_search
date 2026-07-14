import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'
import { createRequire } from 'module'

const require = createRequire(import.meta.url)
const serverConfig = require('./server.config.cjs')

const apiBase = serverConfig.api?.baseUrl || 'http://localhost:8888'
const port = serverConfig.port || 6866
const hostname = serverConfig.development?.hostname || '127.0.0.1'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  define: {
    'import.meta.env.VITE_API_BASE_URL': JSON.stringify(apiBase),
  },
  server: {
    host: hostname,
    port,
    open: serverConfig.development?.open ?? true,
    proxy: {
      '/api': { target: apiBase, changeOrigin: true },
      '/actuator': { target: apiBase, changeOrigin: true },
    },
  },
  preview: {
    host: serverConfig.production?.hostname || '0.0.0.0',
    port: serverConfig.production?.port || port,
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
})
