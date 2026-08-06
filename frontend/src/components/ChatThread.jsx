import { useEffect, useRef, useState } from 'react'

function Icon({ name, filled = false, className = '' }) {
  return (
    <span className={`material-symbols-outlined select-none ${filled ? 'filled' : ''} ${className}`} aria-hidden="true">
      {name}
    </span>
  )
}

function msgTime(d) {
  const t = new Date(d)
  return `${t.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}, ${t.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}`
}

/**
 * Direct-message conversation between a technician and the admin.
 * `viewerIsAdmin` decides which side of the thread is "mine".
 * `onSend(text, file)` must POST and resolve when done.
 * The camera button uses <input capture="environment">, which opens the
 * phone camera directly on mobile and falls back to a file picker on desktop.
 */
export default function ChatThread({ messages, viewerIsAdmin, onSend, emptyHint }) {
  const [text, setText] = useState('')
  const [file, setFile] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const fileRef = useRef(null)
  const cameraRef = useRef(null)
  const bottomRef = useRef(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: 'nearest' })
  }, [messages?.length])

  async function send(e) {
    e.preventDefault()
    if (!text.trim() && !file) return
    setBusy(true)
    setError(null)
    try {
      await onSend(text.trim(), file)
      setText('')
      setFile(null)
      if (fileRef.current) fileRef.current.value = ''
      if (cameraRef.current) cameraRef.current.value = ''
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  function pickFile(e) {
    setFile(e.target.files?.[0] || null)
  }

  return (
    <div className="flex flex-col h-full min-h-0">
      <div className="flex-1 overflow-y-auto p-4 space-y-4 min-h-0">
        {messages === null && <div className="animate-pulse bg-surface-container-high rounded-lg h-16"></div>}
        {messages?.map((m) => {
          const mine = m.fromAdmin === viewerIsAdmin
          return (
            <div key={m.id} className={`flex gap-3 max-w-[85%] ${mine ? 'ml-auto flex-row-reverse' : ''}`}>
              <span className={`w-8 h-8 rounded-full shrink-0 flex items-center justify-center text-xs font-bold mt-1 uppercase ${
                m.fromAdmin ? 'bg-primary text-on-primary' : 'bg-secondary-container text-on-secondary-container'
              }`}>
                {m.fromAdmin ? 'A' : m.author.slice(0, 2)}
              </span>
              <div className={`p-3 rounded-2xl text-sm ${
                mine
                  ? 'bg-primary-container text-on-primary-container rounded-tr-sm'
                  : 'bg-surface-container-low border border-outline-variant/20 rounded-tl-sm'
              }`}>
                {m.body && <p className="whitespace-pre-wrap">{m.body}</p>}
                {m.photoFilename && (
                  <a href={`/api/uploads/${m.photoFilename}`} target="_blank" rel="noreferrer" className={`block ${m.body ? 'mt-2' : ''}`}>
                    <img
                      src={`/api/uploads/${m.photoFilename}`}
                      alt={`Photo from ${m.author}`}
                      className="rounded-lg max-h-48 w-auto border border-outline-variant/30 object-cover"
                    />
                  </a>
                )}
                <p className={`text-[10px] mt-1.5 ${mine ? 'opacity-70 text-left' : 'text-on-surface-variant text-right'}`}>
                  {msgTime(m.createdAt)}
                </p>
              </div>
            </div>
          )
        })}
        {messages?.length === 0 && (
          <p className="text-sm text-on-surface-variant text-center py-6">{emptyHint || 'No messages yet — say hello.'}</p>
        )}
        <div ref={bottomRef}></div>
      </div>

      <form onSubmit={send} className="border-t border-outline-variant/30 p-3 bg-surface-container-lowest">
        <div className="border border-outline-variant rounded-lg bg-surface focus-within:border-primary focus-within:ring-1 focus-within:ring-primary transition-all overflow-hidden">
          <textarea
            value={text}
            onChange={(e) => setText(e.target.value)}
            rows="2"
            placeholder="Type a message..."
            className="w-full p-3 bg-transparent border-none resize-none focus:outline-none text-sm text-on-surface"
          />
          <div className="bg-surface-container-low px-3 py-2 flex justify-between items-center border-t border-outline-variant/30 gap-2">
            <div className="flex items-center gap-3 min-w-0">
              <label className="flex items-center gap-1 text-on-surface-variant hover:text-primary transition-colors cursor-pointer text-xs font-medium" title="Attach a photo">
                <Icon name="attach_file" className="text-[18px]!" />
                <input ref={fileRef} type="file" accept="image/jpeg,image/png,image/webp,image/gif" className="hidden" onChange={pickFile} />
              </label>
              <label className="flex items-center gap-1 text-on-surface-variant hover:text-primary transition-colors cursor-pointer text-xs font-medium" title="Take a photo">
                <Icon name="photo_camera" className="text-[18px]!" />
                <input ref={cameraRef} type="file" accept="image/*" capture="environment" className="hidden" onChange={pickFile} />
              </label>
              {file && (
                <span className="flex items-center gap-1 text-xs text-on-surface-variant min-w-0">
                  <span className="truncate max-w-32">{file.name || 'photo'}</span>
                  <button type="button" onClick={() => { setFile(null); if (fileRef.current) fileRef.current.value = ''; if (cameraRef.current) cameraRef.current.value = '' }} aria-label="Remove photo" className="text-error cursor-pointer">
                    <Icon name="close" className="text-[14px]!" />
                  </button>
                </span>
              )}
            </div>
            <button
              type="submit"
              disabled={busy || (!text.trim() && !file)}
              className="px-4 py-1.5 rounded-lg bg-primary text-on-primary text-sm font-semibold hover:opacity-90 transition-opacity flex items-center gap-1 disabled:opacity-50 cursor-pointer shrink-0"
            >
              {busy ? 'Sending…' : 'Send'} <Icon name="send" className="text-[16px]!" />
            </button>
          </div>
        </div>
        {error && <p className="text-sm text-error mt-2">{error}</p>}
      </form>
    </div>
  )
}
