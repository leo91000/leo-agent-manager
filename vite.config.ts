import tailwindcss from '@tailwindcss/vite'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  server: {
    port: 5178,
    proxy: {
      '/api': 'http://127.0.0.1:4310',
      '/oauth': 'http://127.0.0.1:4310',
    },
  },
  build: { chunkSizeWarningLimit: 400 },
})
