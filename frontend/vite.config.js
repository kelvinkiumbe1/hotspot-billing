import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    // Inline bundled assets as data URIs rather than emitting files, above the
    // 4 KB default.
    //
    // Raised for one specific reason: the payment gateway logos live in the
    // bundle, and the captive portal has no internet until the customer has
    // paid. An emitted .svg is a network request, so it fails on exactly the
    // screen where a missing logo is noticed. Paystack's official mark is 7 KB
    // and was the one crossing the line.
    //
    // Fonts are excluded, and that exclusion is the whole point of this being a
    // function rather than a number. @fontsource ships each weight as a separate
    // woff2 *and* a legacy woff, all of them comfortably under 12 KB, so a bare
    // limit swept up sixty-one font files and base64'd them into the stylesheet:
    // 656 KB of the portal's 775 KB CSS, downloaded in full by every customer
    // before they can pay, on the mobile data they are buying a way out of. And
    // base64 of an already-compressed font barely gzips, so it arrived nearly
    // whole.
    //
    // Emitted instead, the browser fetches only the faces it actually renders,
    // ignores the WOFF1 fallbacks entirely, and caches them across visits. They
    // are same-origin — served by the portal itself, like the stylesheet asking
    // for them — so the walled garden is not a problem.
    assetsInlineLimit: (filePath, content) => {
      if (/\.(woff2?|ttf|otf|eot)$/i.test(filePath)) return false
      return content.length <= 12288
    },
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
