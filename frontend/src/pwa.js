// Progressive-web-app plumbing: register the service worker and capture the
// browser's install prompt so "Install app" can fire the native popup on
// demand instead of showing instructions. Imported for its side effects.

let deferredPrompt = null

if (typeof window !== 'undefined') {
  window.addEventListener('beforeinstallprompt', (e) => {
    // Stash it; Chrome/Edge/Android fire this when the app is installable.
    e.preventDefault()
    deferredPrompt = e
  })
  window.addEventListener('appinstalled', () => { deferredPrompt = null })

  if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
      navigator.serviceWorker.register('/sw.js').catch(() => { /* not fatal */ })
    })
  }
}

/** Fire the native install popup. Returns false when the browser has no
 *  prompt to offer (already installed, or iOS/Safari), so the caller can fall
 *  back to instructions. */
export async function triggerInstall() {
  if (!deferredPrompt) return false
  deferredPrompt.prompt()
  try { await deferredPrompt.userChoice } catch { /* dismissed */ }
  deferredPrompt = null
  return true
}
