import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, INPUT_CLS, LABEL_CLS, Toggle,
} from '../../components/ui.jsx'
import { PORTAL_STRINGS, PORTAL_LANGUAGES } from '../../portalStrings.js'

/**
 * Arranging the captive portal, and rewriting what it says.
 *
 * Two tabs because they are two different jobs. Layout is a handful of choices
 * an operator makes once; Wording is a long list they dip into to change one
 * line. Putting both on one screen would bury the four things that matter under
 * a hundred text boxes.
 *
 * The rule running through the whole screen: blank means "as it ships". Every
 * knob has a Default option and every wording box shows the built-in text as its
 * placeholder, so clearing a field is how you put something back. That makes
 * every change here reversible without a support call, which for a screen that
 * an operator's customers see is the property worth designing around.
 */

const BLOCK_LABELS = {
  promo: { label: 'Promotion banner', hint: 'The countdown strip, when a promotion is running' },
  plans: { label: 'Plans', hint: 'The packages themselves' },
  voucher: { label: 'Voucher box', hint: 'Where a customer types a code they already have' },
  rewards: { label: 'Rewards card', hint: 'Points balance and redeeming, when loyalty is on' },
}

const ALIGNS = [
  { value: '', label: 'As the design does' },
  { value: 'left', label: 'Left' },
  { value: 'centre', label: 'Centre' },
]
const FONTS = [
  { value: '', label: 'As the design does' },
  { value: 'sans', label: 'Sans' },
  { value: 'serif', label: 'Serif' },
  { value: 'rounded', label: 'Rounded' },
  { value: 'mono', label: 'Mono' },
]
const DENSITIES = [
  { value: '', label: 'As the design does' },
  { value: 'compact', label: 'Compact' },
  { value: 'comfortable', label: 'Comfortable' },
  { value: 'spacious', label: 'Spacious' },
]
const LOGO_SIZES = [
  { value: '', label: 'As the design does' },
  { value: 's', label: 'Small' },
  { value: 'm', label: 'Medium' },
  { value: 'l', label: 'Large' },
]

function Choice({ label, hint, options, value, onChange }) {
  return (
    <div>
      <label className={LABEL_CLS}>{label}</label>
      <select className={INPUT_CLS} value={value || ''} onChange={(e) => onChange(e.target.value)}>
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
      {hint && <p className="text-xs text-on-surface-variant mt-1">{hint}</p>}
    </div>
  )
}

/* ------------------------------------------------------------------ Layout */

function LayoutTab({ auth }) {
  const [state, setState] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  const load = () => api('/admin/portal-settings/layout', { auth })
    .then(setState).catch((e) => setMsg({ ok: false, text: e.message }))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!state) return <Skeleton className="h-64" />

  const order = state.order || []
  const hidden = new Set(state.hidden || [])
  const set = (patch) => setState({ ...state, ...patch })

  /* Moves a block one place. Buttons rather than drag-and-drop on purpose: this
     is four rows, it has to work on the phone an operator actually has in the
     field, and a keyboard user gets it for free. */
  function move(index, by) {
    const next = [...order]
    const to = index + by
    if (to < 0 || to >= next.length) return
    ;[next[index], next[to]] = [next[to], next[index]]
    set({ order: next })
  }

  function toggle(block) {
    if (block === state.required) return
    const next = new Set(hidden)
    if (next.has(block)) next.delete(block)
    else next.add(block)
    set({ hidden: [...next] })
  }

  async function save() {
    setBusy(true); setMsg(null)
    try {
      const saved = await api('/admin/portal-settings/layout', {
        method: 'PUT', auth,
        body: {
          order, hidden: [...hidden],
          align: state.align || null,
          radius: state.radius === null || state.radius === undefined ? null : Number(state.radius),
          logoSize: state.logoSize || null,
          headingFont: state.headingFont || null,
          density: state.density || null,
        },
      })
      setState(saved)
      setMsg({ ok: true, text: 'Saved. Open the portal to see it.' })
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setBusy(false) }
  }

  return (
    <div className="space-y-6 max-w-2xl">
      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
        <p className="text-base font-semibold">Sections</p>
        <p className="text-sm text-on-surface-variant mt-1 mb-3">
          The order customers see them in, top to bottom. Anything you switch off simply
          isn&rsquo;t drawn.
        </p>
        <ul className="divide-y divide-outline-variant/40">
          {order.map((block, i) => {
            const meta = BLOCK_LABELS[block] || { label: block, hint: '' }
            const locked = block === state.required
            const off = hidden.has(block)
            return (
              <li key={block} className="flex items-center gap-3 py-2.5">
                <div className="flex flex-col">
                  <button type="button" onClick={() => move(i, -1)} disabled={i === 0}
                    aria-label={`Move ${meta.label} up`}
                    className="cursor-pointer disabled:opacity-25 hover:text-primary">
                    <Icon name="keyboard_arrow_up" className="text-[18px]!" />
                  </button>
                  <button type="button" onClick={() => move(i, 1)} disabled={i === order.length - 1}
                    aria-label={`Move ${meta.label} down`}
                    className="cursor-pointer disabled:opacity-25 hover:text-primary">
                    <Icon name="keyboard_arrow_down" className="text-[18px]!" />
                  </button>
                </div>
                <div className="flex-1 min-w-0">
                  <p className={`text-sm font-medium ${off ? 'line-through opacity-50' : ''}`}>
                    {meta.label}
                  </p>
                  <p className="text-xs text-on-surface-variant">{meta.hint}</p>
                </div>
                {locked
                  ? (
                    <span className="text-xs text-on-surface-variant flex items-center gap-1">
                      <Icon name="lock" className="text-[14px]!" /> always shown
                    </span>
                  )
                  : <Toggle checked={!off} onChange={() => toggle(block)} />}
              </li>
            )
          })}
        </ul>
        <p className="text-xs text-on-surface-variant mt-3 flex items-start gap-2">
          <Icon name="info" className="text-[16px]! mt-0.5" />
          The plans can be moved but not switched off &mdash; a portal without them has
          nothing to sell.
        </p>
      </section>

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 space-y-4">
        <div>
          <p className="text-base font-semibold">Appearance</p>
          <p className="text-sm text-on-surface-variant mt-1">
            Each of these leaves your chosen design alone until you change it.
          </p>
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <Choice label="Text alignment" options={ALIGNS} value={state.align}
            onChange={(v) => set({ align: v })} />
          <Choice label="Heading font" options={FONTS} value={state.headingFont}
            onChange={(v) => set({ headingFont: v })} />
          <Choice label="Spacing" options={DENSITIES} value={state.density}
            onChange={(v) => set({ density: v })} />
          <Choice label="Logo size" options={LOGO_SIZES} value={state.logoSize}
            onChange={(v) => set({ logoSize: v })} />
          <div className="sm:col-span-2">
            <label className={LABEL_CLS}>
              Corner rounding
              {state.radius === null || state.radius === undefined
                ? <span className="normal-case font-normal"> — as the design does</span>
                : <span className="normal-case font-normal"> — {state.radius}px</span>}
            </label>
            <div className="flex items-center gap-3">
              <input type="range" min="0" max="24" step="2" className="flex-1 cursor-pointer"
                value={state.radius ?? 12}
                onChange={(e) => set({ radius: Number(e.target.value) })} />
              <button type="button" onClick={() => set({ radius: null })}
                className="text-xs text-primary cursor-pointer hover:underline whitespace-nowrap">
                Use the design&rsquo;s
              </button>
            </div>
          </div>
        </div>
      </section>

      {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-[#b91c1c]'}`}>{msg.text}</p>}
      <div className="flex gap-2">
        <PrimaryButton disabled={busy} onClick={save}>
          {busy ? 'Saving…' : 'Save layout'}
        </PrimaryButton>
        <a href="/?design=" target="_blank" rel="noreferrer"
          className="px-4 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high">
          Open the portal
        </a>
      </div>
    </div>
  )
}

/* ----------------------------------------------------------------- Wording */

function WordingTab({ auth }) {
  const [lang, setLang] = useState('EN')
  const [overrides, setOverrides] = useState(null)
  const [edits, setEdits] = useState({})
  const [filter, setFilter] = useState('')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  const load = () => api('/admin/portal-copy', { auth })
    .then((d) => { setOverrides(d || {}); setEdits({}) })
    .catch((e) => setMsg({ ok: false, text: e.message }))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  /* Grouped by the prefix on the key, which is already how the string table is
     organised -- "card.*" is the plan cards, "pay.*" the payment screen. Free
     grouping rather than a hand-kept list, so a key added later lands somewhere
     sensible without this file changing. */
  const groups = useMemo(() => {
    const out = {}
    const needle = filter.trim().toLowerCase()
    for (const key of Object.keys(PORTAL_STRINGS.EN)) {
      const original = PORTAL_STRINGS[lang]?.[key] ?? PORTAL_STRINGS.EN[key]
      if (needle && !key.toLowerCase().includes(needle)
        && !String(original).toLowerCase().includes(needle)) continue
      const group = key.includes('.') ? key.slice(0, key.indexOf('.')) : 'other'
      ;(out[group] ||= []).push({ key, original })
    }
    return out
  }, [lang, filter])

  if (!overrides) return <Skeleton className="h-64" />

  const saved = overrides[lang] || {}
  const valueOf = (key) => (key in edits ? edits[key] : (saved[key] ?? ''))
  const changedCount = Object.keys(saved).length

  async function save() {
    setBusy(true); setMsg(null)
    try {
      const r = await api(`/admin/portal-copy/${lang}`, { method: 'PUT', auth, body: edits })
      await load()
      setMsg({ ok: true, text: `Saved ${r.changed} line(s).` })
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setBusy(false) }
  }

  async function reset() {
    setBusy(true); setMsg(null)
    try {
      const r = await api(`/admin/portal-copy/${lang}/reset`, { method: 'POST', auth })
      await load()
      setMsg({ ok: true, text: `Restored ${r.restored} line(s) to the original wording.` })
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setBusy(false) }
  }

  return (
    <div className="space-y-5 max-w-3xl">
      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
        <div className="flex flex-wrap items-end gap-4">
          <div>
            <label className={LABEL_CLS}>Language</label>
            <div className="flex gap-2">
              {PORTAL_LANGUAGES.map((l) => (
                <button key={l.code} type="button" onClick={() => { setLang(l.code); setEdits({}) }}
                  aria-pressed={lang === l.code}
                  className={`px-3 py-1.5 rounded-full text-sm cursor-pointer transition-colors ${
                    lang === l.code
                      ? 'bg-primary-container text-on-primary-container font-semibold'
                      : 'border border-outline-variant hover:bg-surface-container-high'
                  }`}>
                  {l.name}
                </button>
              ))}
            </div>
          </div>
          <div className="flex-1 min-w-[12rem]">
            <label className={LABEL_CLS}>Find</label>
            <input className={INPUT_CLS} value={filter} placeholder="a word, or part of a key"
              onChange={(e) => setFilter(e.target.value)} />
          </div>
        </div>
        <p className="text-xs text-on-surface-variant mt-3 flex items-start gap-2">
          <Icon name="info" className="text-[16px]! mt-0.5" />
          The grey text is what it says today. Type over it to change it, or clear the box to
          put the original back. {changedCount > 0
            ? `You have changed ${changedCount} line(s) in this language.`
            : 'Nothing is changed in this language yet.'}
        </p>
      </section>

      {Object.keys(groups).sort().map((group) => (
        <section key={group}
          className="bg-surface-container-lowest rounded-lg border border-outline-variant/40">
          <p className="text-xs font-semibold uppercase tracking-wider text-on-surface-variant px-4 pt-3">
            {group}
          </p>
          <ul className="divide-y divide-outline-variant/40">
            {groups[group].map(({ key, original }) => {
              const isChanged = key in saved || (key in edits && edits[key] !== '')
              return (
                <li key={key} className="px-4 py-2.5">
                  <div className="flex items-center gap-2">
                    <code className="text-[11px] font-mono text-on-surface-variant">{key}</code>
                    {isChanged && (
                      <span className="text-[10px] font-semibold text-primary uppercase tracking-wide">
                        changed
                      </span>
                    )}
                  </div>
                  <input className={`${INPUT_CLS} mt-1`} value={valueOf(key)}
                    placeholder={original}
                    onChange={(e) => setEdits({ ...edits, [key]: e.target.value })} />
                </li>
              )
            })}
          </ul>
        </section>
      ))}

      {Object.keys(groups).length === 0 && (
        <p className="text-sm text-on-surface-variant">Nothing matches “{filter}”.</p>
      )}

      {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-[#b91c1c]'}`}>{msg.text}</p>}
      <div className="flex flex-wrap gap-2">
        <PrimaryButton disabled={busy || Object.keys(edits).length === 0} onClick={save}>
          {busy ? 'Saving…' : `Save ${Object.keys(edits).length || ''} change(s)`.trim()}
        </PrimaryButton>
        {changedCount > 0 && (
          <button type="button" onClick={reset} disabled={busy}
            className="px-4 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high disabled:opacity-50">
            Restore all original wording
          </button>
        )}
      </div>
    </div>
  )
}

/* -------------------------------------------------------------------------- */

export default function PortalLayoutPage({ auth }) {
  const [tab, setTab] = useState('layout')
  return (
    <>
      <PageHeader title="Portal layout & wording"
        subtitle="Arrange the sections customers see, and change any word on the page." />
      <div className="flex gap-2 mb-5">
        {[['layout', 'Layout'], ['wording', 'Wording']].map(([key, label]) => (
          <button key={key} type="button" onClick={() => setTab(key)} aria-pressed={tab === key}
            className={`px-4 py-2 rounded-full text-sm cursor-pointer transition-colors ${
              tab === key
                ? 'bg-primary-container text-on-primary-container font-semibold'
                : 'border border-outline-variant hover:bg-surface-container-high'
            }`}>
            {label}
          </button>
        ))}
      </div>
      {tab === 'layout' ? <LayoutTab auth={auth} /> : <WordingTab auth={auth} />}
    </>
  )
}
