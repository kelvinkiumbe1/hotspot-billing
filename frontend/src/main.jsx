import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
// Self-hosted so they work on the captive portal, which has no internet
// until the customer has paid — a Google Fonts CDN link would fail exactly
// where it matters most. Only the weights actually used are bundled.
import '@fontsource/fira-sans/400.css'
import '@fontsource/fira-sans/500.css'
import '@fontsource/fira-sans/600.css'
import '@fontsource/fira-sans/700.css'
import '@fontsource/fira-code/400.css'
import '@fontsource/fira-code/500.css'
import '@fontsource/fira-code/600.css'
import './index.css'
import './pwa.js'
import App from './App.jsx'

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
