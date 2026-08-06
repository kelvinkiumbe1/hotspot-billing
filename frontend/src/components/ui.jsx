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
export function PageHeader({ title, subtitle, children }) {
  return (
    <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 mb-6">
      <div>
        <h2 className="text-4xl font-bold tracking-tight text-on-background">{title}</h2>
        {subtitle && <p className="text-base text-on-surface-variant mt-1">{subtitle}</p>}
      </div>
      {children}
    </div>
  )
}

/** Primary action button styled like the rest of the dashboard. */
export function PrimaryButton({ children, className = '', ...props }) {
  return (
    <button
      {...props}
      className={`bg-primary text-on-primary text-lg font-semibold px-6 py-3 rounded-lg flex items-center gap-2 shadow-[0_4px_12px_rgba(15,23,42,0.08)] hover:bg-surface-tint transition-all active:scale-95 whitespace-nowrap min-h-[48px] disabled:opacity-60 cursor-pointer ${className}`}
    >
      {children}
    </button>
  )
}

export function StatCard({ label, value, accent, hint }) {
  return (
    <div className={`bg-surface-container-lowest p-4 rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] border border-surface-variant/30 ${accent ? `border-t-4 ${accent}` : ''}`}>
      <CardLabel>{label}</CardLabel>
      <div className="text-3xl font-bold tracking-tight mt-2 text-on-background tabular-nums">{value}</div>
      {hint && <p className="text-xs text-on-surface-variant mt-1">{hint}</p>}
    </div>
  )
}
