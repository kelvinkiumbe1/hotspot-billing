import { useEffect, useRef, useState } from 'react'
import { api } from '../api.js'

import { Icon } from './icons.jsx'

function noteTime(d) {
  const t = new Date(d)
  return `${t.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}, ${t.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}`
}

/**
 * Comment + photo thread on a maintenance task. Used by both the admin
 * maintenance panel and the technician task detail (the /api/tech notes
 * endpoints accept both roles; the backend tags each note with its role).
 */
export default function TaskNotes({ auth, taskId }) {
  const [notes, setNotes] = useState(null)
  const [text, setText] = useState('')
  const [file, setFile] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const fileRef = useRef(null)

  const load = () => api(`/tech/tasks/${taskId}/notes`, { auth }).then(setNotes).catch(() => setNotes([]))
  useEffect(() => { setNotes(null); load() }, [taskId, auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function send(e) {
    e.preventDefault()
    if (!text.trim() && !file) return
    setBusy(true)
    setError(null)
    try {
      const form = new FormData()
      if (text.trim()) form.append('message', text.trim())
      if (file) form.append('photo', file)
      await api(`/tech/tasks/${taskId}/notes`, { method: 'POST', auth, body: form })
      setText('')
      setFile(null)
      if (fileRef.current) fileRef.current.value = ''
      load()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      <div className="flex flex-col gap-3 mb-3 max-h-72 overflow-y-auto">
        {notes === null && <div className="animate-pulse bg-surface-container-high rounded-lg h-16"></div>}
        {notes?.map((n) => (
          <div key={n.id} className={`p-3 rounded-xl text-sm ${n.fromAdmin ? 'bg-primary-container/15 border border-primary-container/20' : 'bg-surface-container-low border border-outline-variant/20'}`}>
            <div className="flex justify-between items-center mb-1 gap-2">
              <span className="font-semibold text-on-surface capitalize flex items-center gap-1.5">
                {n.author}
                <span className={`text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded ${n.fromAdmin ? 'bg-primary-container text-on-primary-container' : 'bg-secondary-container text-on-secondary-container'}`}>
                  {n.fromAdmin ? 'Admin' : 'Field'}
                </span>
              </span>
              <span className="text-xs text-on-surface-variant whitespace-nowrap">{noteTime(n.createdAt)}</span>
            </div>
            {n.body && <p className="text-on-surface whitespace-pre-wrap">{n.body}</p>}
            {n.photoFilename && (
              <a href={`/api/uploads/${n.photoFilename}`} target="_blank" rel="noreferrer" className="block mt-2">
                <img
                  src={`/api/uploads/${n.photoFilename}`}
                  alt={`Site photo from ${n.author}`}
                  className="rounded-lg max-h-48 w-auto border border-outline-variant/30 object-cover"
                />
              </a>
            )}
          </div>
        ))}
        {notes?.length === 0 && <p className="text-sm text-on-surface-variant">No notes yet — start the conversation below.</p>}
      </div>

      <form onSubmit={send} className="border border-outline-variant rounded-lg bg-surface focus-within:border-primary focus-within:ring-1 focus-within:ring-primary transition-all overflow-hidden">
        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          rows="2"
          placeholder="Write an update for this task..."
          className="w-full p-3 bg-transparent border-none resize-none focus:outline-none text-sm text-on-surface"
        />
        <div className="bg-surface-container-low px-3 py-2 flex justify-between items-center border-t border-outline-variant/30 gap-2">
          <div className="flex items-center gap-3 min-w-0">
            <label className="flex items-center gap-1 text-on-surface-variant hover:text-primary transition-colors cursor-pointer text-xs font-medium" title="Attach a photo">
              <Icon name="attach_file" className="text-[18px]!" />
              <input
                ref={fileRef}
                type="file"
                accept="image/jpeg,image/png,image/webp,image/gif"
                className="hidden"
                onChange={(e) => setFile(e.target.files?.[0] || null)}
              />
            </label>
            <label className="flex items-center gap-1 text-on-surface-variant hover:text-primary transition-colors cursor-pointer text-xs font-medium" title="Take a photo">
              <Icon name="photo_camera" className="text-[18px]!" />
              <input
                type="file"
                accept="image/*"
                capture="environment"
                className="hidden"
                onChange={(e) => setFile(e.target.files?.[0] || null)}
              />
            </label>
            {file && <span className="text-xs text-on-surface-variant truncate max-w-32">{file.name || 'photo'}</span>}
          </div>
          <button
            type="submit"
            disabled={busy || (!text.trim() && !file)}
            className="px-4 py-1.5 rounded-lg bg-primary text-on-primary text-sm font-semibold hover:opacity-90 transition-opacity flex items-center gap-1 disabled:opacity-50 cursor-pointer shrink-0"
          >
            {busy ? 'Sending…' : 'Send'} <Icon name="send" className="text-[16px]!" />
          </button>
        </div>
        {error && <p className="text-sm text-error px-3 pb-2">{error}</p>}
      </form>
    </div>
  )
}
