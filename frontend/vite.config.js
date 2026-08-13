import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // Bind to all interfaces so the dev server is reachable over the LAN / a
    // tunnel (e.g. ngrok) for previewing on a phone. allowedHosts disables the
    // host check so a tunnel's *.ngrok-free.app hostname isn't rejected. Dev
    // convenience only — production is a real build behind a proper server.
    host: true,
    allowedHosts: true,
    proxy: {
      '/api': 'http://localhost:8081',
    },
  },
})
