/**
 * Small shared building blocks for the admin pages, using the design
 * tokens from index.css.
 */

export function Icon({ name, filled = false, className = '' }) {
  return (
    <span className={`material-symbols-outlined select-none ${filled ? 'filled' : ''} ${className}`} aria-hidden="true">
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
      <div className="text-[22px] leading-tight font-semibold mt-0.5 text-on-surface tabular-nums">
        {value}
      </div>
      {hint && <p className="text-[11px] text-on-surface-variant mt-0.5 truncate" title={hint}>{hint}</p>}
    </div>
  )
}
