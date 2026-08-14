import { useEffect, useState } from 'react'
import { api } from '../api.js'
import { Icon } from '../components/ui.jsx'

function when(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleString(undefined, {
    day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit',
  })
}

function duration(minutes) {
  if (minutes < 60) return `${minutes} min`
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  return m ? `${h}h ${m}m` : `${h}h`
}

/**
 * A public status page. A customer who can see for themselves that the fault
 * is known and being worked on is a support call that never happens — and
 * during an outage those calls all arrive at once, from the people the
 * operator is least able to answer while fixing it.
 *
 * <p>Deliberately plain and free of any customer detail: areas and times only,
 * readable on a phone with one bar of signal.
 */
export default function Status() {
  const [data, setData] = useState(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    const load = () => api('/status').then(setData).catch(() => setFailed(true))
    load()
    const id = setInterval(load, 60000)
    return () => clearInterval(id)
  }, [])

  if (failed) {
    return (
      <main className="min-h-screen bg-background text-on-background flex items-center justify-center p-6">
        <p className="text-on-surface-variant">Status is unavailable right now.</p>
      </main>
    )
  }
  if (!data) {
    return <main className="min-h-screen bg-background" />
  }
  if (data.enabled === false) {
    return (
      <main className="min-h-screen bg-background text-on-background flex items-center justify-center p-6">
        <p className="text-on-surface-variant">This page isn't published.</p>
      </main>
    )
  }

  const current = data.current || []
  const ok = data.operational

  return (
    <main className="min-h-screen bg-background text-on-background">
      <div className="max-w-2xl mx-auto px-6 py-12">
        <p className="text-[11px] font-semibold tracking-[0.3em] uppercase text-on-surface-variant mb-3">
          {data.business}
        </p>

        <div className={`rounded-2xl border p-6 ${
          ok ? 'border-secondary/40 bg-secondary/5' : 'border-error/40 bg-error/5'
        }`}>
          <div className="flex items-center gap-3">
            <Icon name={ok ? 'check_circle' : 'error'}
              className={`text-[32px]! ${ok ? 'text-secondary' : 'text-error'}`} />
            <div>
              <h1 className="text-2xl font-bold tracking-tight">
                {ok ? 'All systems normal' : 'We have a problem'}
              </h1>
              <p className="text-sm text-on-surface-variant mt-0.5">
                {ok
                  ? 'The network is up everywhere we monitor.'
                  : 'Our team is working on it. You will not lose the time you have paid for.'}
              </p>
            </div>
          </div>
        </div>

        {current.length > 0 && (
          <section className="mt-6">
            <h2 className="text-sm font-semibold mb-2">Happening now</h2>
            <div className="rounded-xl border border-outline-variant divide-y divide-outline-variant">
              {current.map((i) => (
                <div key={i.id} className="p-4">
                  <p className="font-semibold">{i.area}</p>
                  <p className="text-sm text-on-surface-variant mt-0.5">
                    Down for {duration(i.minutes)}, since {when(i.startedAt)}.
                    {' '}Expected back within {data.etaMinutes} minutes of the start.
                  </p>
                </div>
              ))}
            </div>
          </section>
        )}

        {(data.recent || []).length > 0 && (
          <section className="mt-8">
            <h2 className="text-sm font-semibold mb-2">Past two weeks</h2>
            <div className="rounded-xl border border-outline-variant divide-y divide-outline-variant">
              {data.recent.map((i) => (
                <div key={i.id} className="px-4 py-3 flex flex-wrap items-baseline gap-x-3">
                  <span className="text-sm font-medium">{i.area}</span>
                  <span className="text-xs text-on-surface-variant flex-1">
                    {when(i.startedAt)} — back after {duration(i.minutes)}
                  </span>
                  <span className="text-xs text-secondary font-semibold">Resolved</span>
                </div>
              ))}
            </div>
          </section>
        )}

        <p className="mt-8 text-xs text-on-surface-variant">
          This page updates itself every minute. If your connection is down and nothing is listed here,
          the fault may be on your own line — please contact support.
        </p>
      </div>
    </main>
  )
}
