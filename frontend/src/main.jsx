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
import { setCurrency } from './money.js'
import { setCountry } from './phone.js'

// Learn how this operator writes money before the first screen paints. Done
// here rather than per page because prices appear on nearly all of them, and
// the endpoint is public — the captive portal has to reach it before anyone
// has paid for anything. A failure leaves the shillings default in place,
// which is right for every deployment that existed before currency was a
// setting.
fetch('/api/portal-settings')
  .then((r) => (r.ok ? r.json() : null))
  .then((s) => {
    setCurrency(s?.currency)
    // The same trip learns the country, which decides what shape a
    // phone number has to be. Every page needs it, not just the ones
    // that happen to load settings themselves.
    setCountry(s?.country)
  })
  .catch(() => {})

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
