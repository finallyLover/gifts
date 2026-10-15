import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// Vite 构建配置：固定 1420 端口供 Tauri devUrl 使用
export default defineConfig({
  plugins: [vue()],
  clearScreen: false,
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 1420,
    strictPort: true
  }
})
