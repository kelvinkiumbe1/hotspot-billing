/**
 * Small shared building blocks for the admin pages, using the design
 * tokens from index.css.
 */
import { useId, useLayoutEffect, useRef, useState } from 'react'

export function Icon({ name, filled = false, className = '', style }) {
  return (
    <span
      className={`material-symbols-outlined select-none ${filled ? 'filled' : ''} ${className}`}
      style={style}
      aria-hidden="true"
    >
      {name}
    </span>
  )
}

export function Skeleton({ className = '' }) {
  return <div className={`animate-pulse bg-surface-container-high rounded-xl ${className}`}></div>
}

export function CardLabel({ children }) {
  return <h3 className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant">{children}</h3>
}

export function Toggle({ checked, onChange }) {
  return (
    <label className="relative inline-flex items-center cursor-pointer">
      <input type="checkbox" className="sr-only peer" checked={checked} onChange={onChange} />
      <div className="w-9 h-5 bg-surface-variant rounded-full peer peer-checked:bg-primary after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border after:border-gray-300 after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:after:translate-x-full peer-checked:after:border-white"></div>
    </label>
  )
}

export function fmtKES(n) {
  return `KES ${Number(n || 0).toLocaleString()}`
}

export function fmtDate(d) {
  if (!d) return '—'
  return new Date(d).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

export function fmtTime(d) {
  if (!d) return ''
  return new Date(d).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
}

export function relativeTime(d) {
  if (!d) return '—'
  const diff = Date.now() - new Date(d).getTime()
  const mins = Math.floor(diff / 60000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins} min${mins > 1 ? 's' : ''} ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs} hour${hrs > 1 ? 's' : ''} ago`
  const days = Math.floor(hrs / 24)
  return `${days} day${days > 1 ? 's' : ''} ago`
}

export const INPUT_CLS =
  'w-full bg-surface border border-outline-variant rounded-lg px-4 py-3 text-base text-on-surface focus:border-primary focus:ring-2 focus:ring-primary/20 outline-none transition-all min-h-[48px]'

export const LABEL_CLS = 'block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2'

/** Page heading with optional action button on the right. */
/**
 * Page heading. Deliberately small: a 36px marketing title on every screen
 * cost about 60px of vertical space and told a daily user nothing they did
 * not already know from the navigation. The subtitle sits alongside on wide
 * screens rather than stacking.
 */
export function PageHeader({ title, subtitle, children }) {
  return (
    <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-2 mb-4 pb-3 border-b border-outline-variant">
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1 min-w-0">
        <h2 className="text-xl font-semibold tracking-tight text-on-surface">{title}</h2>
        {subtitle && <p className="text-xs text-on-surface-variant">{subtitle}</p>}
      </div>
      {children && <div className="flex items-center gap-2 shrink-0">{children}</div>}
    </div>
  )
}

/** Primary action button styled like the rest of the dashboard. */
/**
 * Primary action. 40px tall rather than 48 — still a comfortable target on
 * a desktop console, without the button dominating the row it sits in.
 */
export function PrimaryButton({ children, className = '', ...props }) {
  return (
    <button
      {...props}
      className={`bg-primary text-on-primary text-sm font-semibold px-4 rounded-md flex items-center gap-1.5 hover:opacity-90 transition-opacity active:scale-[0.98] whitespace-nowrap h-10 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer ${className}`}
    >
      {children}
    </button>
  )
}

/**
 * A single figure. Sized as a reading, not a headline — four of these used
 * to occupy a fifth of the screen with numbers nobody came for. The accent
 * shows as a left rule rather than a heavy top border, so a row of them
 * reads as one strip instead of four competing boxes.
 */
export function StatCard({ label, value, accent, hint }) {
  return (
    <div
      className={`bg-surface-container-lowest border border-outline-variant rounded-md px-3.5 py-2.5 ${
        accent ? `border-l-2 ${accent.replace('border-t-', 'border-l-')}` : ''
      }`}
    >
      <p className="text-[11px] font-medium tracking-[0.04em] uppercase text-on-surface-variant truncate">
        {label}
      </p>
      <div className="font-mono text-[22px] leading-tight font-semibold mt-0.5 text-on-surface tabular-nums">
        {value}
      </div>
      {hint && <p className="text-[11px] text-on-surface-variant mt-0.5 truncate" title={hint}>{hint}</p>}
    </div>
  )
}

/** Catmull-Rom spline → cubic bezier, so a run of points reads as one
 *  smooth curve rather than a jagged polyline. */
function smoothLine(pts) {
  if (pts.length < 2) return pts.length ? `M ${pts[0][0]},${pts[0][1]}` : ''
  let d = `M ${pts[0][0]},${pts[0][1]}`
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[i - 1] || pts[i]
    const p1 = pts[i]
    const p2 = pts[i + 1]
    const p3 = pts[i + 2] || p2
    const c1x = p1[0] + (p2[0] - p0[0]) / 6
    const c1y = p1[1] + (p2[1] - p0[1]) / 6
    const c2x = p2[0] - (p3[0] - p1[0]) / 6
    const c2y = p2[1] - (p3[1] - p1[1]) / 6
    d += ` C ${c1x.toFixed(2)},${c1y.toFixed(2)} ${c2x.toFixed(2)},${c2y.toFixed(2)} ${p2[0].toFixed(2)},${p2[1].toFixed(2)}`
  }
  return d
}

/**
 * A gradient-filled area chart sized for a card: a headline figure sits
 * beside it, the line shows the shape of the period. Pure SVG so it adds
 * no chart dependency; width is measured rather than assumed so the dot
 * and the hover tooltip land on the right point at any card size.
 *
 * `data` is an array of plain numbers, oldest first. `labels` (optional,
 * same length) name each point in the tooltip — usually the date.
 */
export function AreaSparkline({ data, color = 'var(--color-primary)', height = 64, format = (v) => v, labels }) {
  const wrapRef = useRef(null)
  const [w, setW] = useState(0)
  const [hover, setHover] = useState(null)
  const gid = useId().replace(/:/g, '')

  useLayoutEffect(() => {
    const el = wrapRef.current
    if (!el) return
    const ro = new ResizeObserver((entries) => setW(entries[0].contentRect.width))
    ro.observe(el)
    setW(el.clientWidth)
    return () => ro.disconnect()
  }, [])

  const n = data?.length ?? 0
  const padX = 4
  const padY = 6
  const innerW = Math.max(0, w - padX * 2)
  const innerH = height - padY * 2
  const min = n ? Math.min(...data) : 0
  const max = n ? Math.max(...data) : 1
  const span = max - min || 1

  const x = (i) => (n <= 1 ? padX + innerW / 2 : padX + (i / (n - 1)) * innerW)
  // A flat series sits on the mid-line rather than pinned to the floor.
  const y = (v) => (max === min ? padY + innerH / 2 : padY + innerH - ((v - min) / span) * innerH)

  const pts = n ? data.map((v, i) => [x(i), y(v)]) : []
  const line = smoothLine(pts)
  const area = n
    ? `${line} L ${x(n - 1).toFixed(2)},${height} L ${x(0).toFixed(2)},${height} Z`
    : ''

  function onMove(e) {
    if (!n || !w) return
    const rect = wrapRef.current.getBoundingClientRect()
    const rel = e.clientX - rect.left
    const idx = Math.round(((rel - padX) / (innerW || 1)) * (n - 1))
    setHover(Math.max(0, Math.min(n - 1, idx)))
  }

  const hx = hover != null ? x(hover) : 0
  const hy = hover != null ? y(data[hover]) : 0
  // Keep the tooltip inside the card rather than clipping past an edge.
  const tipSide = hover != null && hx > w * 0.6 ? 'right' : 'left'

  return (
    <div ref={wrapRef} className="relative w-full" style={{ height }}>
      {w > 0 && n > 0 && (
        <svg
          width={w}
          height={height}
          className="block overflow-visible"
          onMouseMove={onMove}
          onMouseLeave={() => setHover(null)}
        >
          <defs>
            <linearGradient id={`area-${gid}`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={color} stopOpacity="0.28" />
              <stop offset="100%" stopColor={color} stopOpacity="0.02" />
            </linearGradient>
          </defs>
          <path d={area} fill={`url(#area-${gid})`} />
          <path d={line} fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
          {hover != null && (
            <>
              <line x1={hx} y1={padY} x2={hx} y2={height} stroke={color} strokeWidth="1" strokeDasharray="2 2" opacity="0.5" />
              <circle cx={hx} cy={hy} r="4" fill={color} stroke="var(--color-surface-container-lowest)" strokeWidth="2" />
            </>
          )}
          {/* Full-width hit area, so the pointer is tracked even over the gap
              above the line where there is no path to hover. */}
          <rect x="0" y="0" width={w} height={height} fill="transparent" />
        </svg>
      )}
      {hover != null && (
        <div
          className="pointer-events-none absolute z-10 whitespace-nowrap rounded-md border border-outline-variant bg-surface-container-high px-2 py-1 shadow-lg"
          style={{
            top: Math.max(0, hy - 34),
            left: tipSide === 'left' ? hx : undefined,
            right: tipSide === 'right' ? w - hx : undefined,
          }}
        >
          <p className="text-xs font-semibold text-on-surface tabular-nums">{format(data[hover])}</p>
          {labels?.[hover] && <p className="text-[10px] text-on-surface-variant">{labels[hover]}</p>}
        </div>
      )}
    </div>
  )
}
