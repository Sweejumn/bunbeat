import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    // Atomic writes from some editors/tools create temp dirs that crash
    // native Windows file watchers (EBUSY); polling avoids that entirely.
    watch: { usePolling: true },
    proxy: {
      // Forward API calls to the FastAPI backend in dev mode.
      '/api': {
        target: 'http://127.0.0.1:8000',
        changeOrigin: true,
      },
    },
  },
})
