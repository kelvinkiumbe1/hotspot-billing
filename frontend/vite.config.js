import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    // Inline every bundled asset as a data URI rather than emitting files.
    //
    // Raised from the 4 KB default for one specific reason: the payment gateway
    // logos live in the bundle, and the captive portal has no internet until the
    // customer has paid. An emitted .svg is a network request, so it fails on
    // exactly the screen where a missing logo is noticed. Paystack's official
    // mark is 7 KB and was the one crossing the line.
    assetsInlineLimit: 12288,
  },
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
