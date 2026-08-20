import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, StatCard, relativeTime, INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

/**
 * The routers in customers' houses.
 *
 * Everything here queues rather than does, and the screen says so, because
 * TR-069 only lets us answer a router that calls in -- never call it. A change is
 * either picked up at the next check-in or a connection request pokes the box into
 * calling now, and which of those happened is the difference between "your
 * password is changed" and "your password will change within the hour". Somebody
 * on the phone to a customer needs to know which they are saying.
 *
 * Nobody adds a device here. A router pointed at this ACS introduces itself and
 * appears; if the list is empty, the routers have not been pointed here yet.
 */

const SETTINGS = [
  ['WIFI_SSID', 'WiFi name', 'text'],
  ['WIFI_PASSWORD', 'WiFi password', 'text'],
  ['ADMIN_PASSWORD', 'Router admin password', 'password'],
]

function StatusPill({ device }) {
  // "Seen recently" rather than "online": TR-069 has no presence. A box that
  // checked in twenty minutes ago is almost certainly fine and claiming it is
  // online would be a guess.
  const seen = device.lastInformAt ? new Date(device.lastInformAt).getTime() : 0
  const fresh = seen && (Date.now() - seen) < 2 * 60 * 60 * 1000
  return (
    <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium ${
      fresh ? 'bg-secondary-container text-on-secondary-container'
        : 'bg-surface-container-high text-on-surface-variant'}`}>
      <span className={`w-1.5 h-1.5 rounded-full ${fresh ? 'bg-secondary' : 'bg-outline'}`}></span>
      {device.lastInformAt ? relativeTime(device.lastInformAt) : 'never seen'}
    </span>
  )
}

function DeviceDetail({ auth, id, onClose }) {
  const [device, setDevice] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const [edits, setEdits] = useState({})

  const load = () => api(`/admin/cpe/${id}`, { auth }).then(setDevice).catch(() => {})
  useEffect(() => { load() }, [id, auth]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!device) return <Skeleton className="h-64" />

  async function act(path, body, what) {
    setBusy(true); setMsg(null)
    try {
      const r = await api(`/admin/cpe/${id}/${path}`, { method: 'POST', auth, body })
      // The server's own wording. It knows whether the box was reachable and this
      // screen must not soften that into "done".
      setMsg({ ok: true, text: r.message || what })
      setEdits({})
      load()
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setBusy(false) }
  }

  const changed = Object.entries(edits).filter(([, v]) => v && v.trim())

  return (
    <div className="fixed inset-0 bg-black/40 flex items-start justify-center p-4 z-50 overflow-y-auto">
      <div className="bg-surface rounded-xl w-full max-w-2xl my-8 p-5 space-y-5">
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <p className="text-lg font-semibold truncate">
              {device.manufacturer || 'Router'} {device.productClass || ''}
            </p>
            <p className="text-xs font-mono text-on-surface-variant">{device.serialNumber}</p>
          </div>
          <button type="button" onClick={onClose} className="cursor-pointer">
            <Icon name="close" />
          </button>
        </div>

        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
          <div><p className="text-xs text-on-surface-variant">Last check-in</p>
            <StatusPill device={device} /></div>
          <div><p className="text-xs text-on-surface-variant">Firmware</p>
            <p>{device.softwareVersion || '—'}</p></div>
          <div><p className="text-xs text-on-surface-variant">Data model</p>
            <p>{device.dataModel}</p></div>
          <div><p className="text-xs text-on-surface-variant">Can be poked</p>
            <p>{device.reachable ? 'Yes' : 'No — behind NAT'}</p></div>
        </div>

        {!device.reachable && (
          <p className="text-xs text-on-surface-variant flex items-start gap-2">
            <Icon name="info" className="text-[16px]! mt-0.5" />
            This router has not told us an address we can reach, which usually means it is
            behind the mobile network&rsquo;s NAT. Changes still work &mdash; they apply the
            next time it checks in rather than straight away.
          </p>
        )}

        <div className="rounded-lg border border-outline-variant p-4 space-y-3">
          <p className="text-sm font-semibold">Change something</p>
          {SETTINGS.map(([key, label, type]) => (
            <div key={key}>
              <label className={LABEL_CLS}>{label}</label>
              <input className={INPUT_CLS} type={type} value={edits[key] || ''}
                placeholder="leave blank to keep it"
                onChange={(e) => setEdits({ ...edits, [key]: e.target.value })} />
            </div>
          ))}
          <PrimaryButton disabled={busy || changed.length === 0}
            onClick={() => act('settings',
              { settings: Object.fromEntries(changed), now: true }, 'Queued')}>
            {busy ? 'Sending…' : `Apply ${changed.length || ''} change(s)`.trim()}
          </PrimaryButton>
        </div>

        <div className="flex flex-wrap gap-2">
          <button type="button" disabled={busy} onClick={() => act('refresh', { now: true },
            'Reading values back')}
            className="px-3 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high">
            Read its settings
          </button>
          <button type="button" disabled={busy} onClick={() => act('reboot', { now: true },
            'Reboot queued')}
            className="px-3 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high">
            Reboot
          </button>
          <button type="button" disabled={busy}
            onClick={() => {
              // A factory reset wipes the customer's own WiFi name and password.
              // Worth one deliberate pause.
              if (window.confirm('This wipes the router back to factory settings, '
                + 'including the customer’s WiFi name and password. Continue?')) {
                act('factory-reset', { now: true }, 'Factory reset queued')
              }
            }}
            className="px-3 py-2 rounded-lg border border-error text-error text-sm cursor-pointer hover:bg-error/5">
            Factory reset
          </button>
        </div>

        {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-[#b91c1c]'}`}>{msg.text}</p>}

        {device.tasks && device.tasks.length > 0 && (
          <details>
            <summary className="cursor-pointer text-sm font-semibold">
              What has been asked of it
            </summary>
            <ul className="mt-2 divide-y divide-outline-variant/40 text-sm">
              {device.tasks.slice(0, 15).map((t) => (
                <li key={t.id} className="py-2 flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p>{t.kind.replace(/_/g, ' ').toLowerCase()}</p>
                    {t.fault && <p className="text-xs text-error">{t.fault}</p>}
                  </div>
                  <span className="text-xs text-on-surface-variant whitespace-nowrap">
                    {t.status} · {relativeTime(t.createdAt)}
                  </span>
                </li>
              ))}
            </ul>
          </details>
        )}

        {device.parameters && Object.keys(device.parameters).length > 0 && (
          <details>
            <summary className="cursor-pointer text-sm font-semibold">
              What it last told us
            </summary>
            <div className="mt-2 overflow-x-auto">
              <table className="w-full text-xs font-mono">
                <tbody className="divide-y divide-outline-variant/40">
                  {Object.entries(device.parameters).map(([k, v]) => (
                    <tr key={k}>
                      <td className="py-1 pr-3 text-on-surface-variant break-all">{k}</td>
                      <td className="py-1 break-all">{v}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </details>
        )}
      </div>
    </div>
  )
}

export default function CpePage({ auth }) {
  const [data, setData] = useState(null)
  const [open, setOpen] = useState(null)
  const [filter, setFilter] = useState('')

  const load = () => api('/admin/cpe', { auth }).then(setData).catch(() => setData({ devices: [] }))
  useEffect(() => {
    load()
    const t = setInterval(load, 60000)
    return () => clearInterval(t)
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  const shown = useMemo(() => {
    const rows = data?.devices || []
    const needle = filter.trim().toLowerCase()
    if (!needle) return rows
    return rows.filter((d) => [d.serialNumber, d.manufacturer, d.productClass]
      .some((v) => (v || '').toLowerCase().includes(needle)))
  }, [data, filter])

  if (!data) return <Skeleton className="h-64" />

  return (
    <>
      <PageHeader title="Customer routers"
        subtitle="Every router pointed at this system, and what you can change on it from here." />

      {data.devices.length === 0 ? (
        <div className="rounded-lg border border-outline-variant p-6 text-center">
          <Icon name="router" className="text-[32px]! text-on-surface-variant" />
          <p className="text-base font-semibold mt-2">No router has checked in yet</p>
          <p className="text-sm text-on-surface-variant mt-1 max-w-lg mx-auto">
            Routers appear here on their own once their TR-069 settings point at this
            server. Nobody adds them by hand &mdash; set the ACS URL on the router (or in
            the batch you ship them with) and it will introduce itself.
          </p>
        </div>
      ) : (
        <div className="space-y-4">
          <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
            <StatCard label="Routers known" value={data.count} />
            <StatCard label="Seen in the last 2 hours"
              value={data.devices.filter((d) => d.lastInformAt
                && Date.now() - new Date(d.lastInformAt).getTime() < 7200000).length} />
            <StatCard label="Can be changed instantly"
              value={data.devices.filter((d) => d.reachable).length} />
          </div>

          <input className={INPUT_CLS} value={filter} placeholder="serial, make or model"
            onChange={(e) => setFilter(e.target.value)} />

          <div className="overflow-x-auto rounded-lg border border-outline-variant">
            <table className="w-full text-sm">
              <thead className="bg-surface-container-low text-on-surface-variant">
                <tr>
                  <th className="text-left font-medium px-3 py-2">Router</th>
                  <th className="text-left font-medium px-3 py-2">Firmware</th>
                  <th className="text-left font-medium px-3 py-2">Last check-in</th>
                  <th className="px-3 py-2"></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-outline-variant/40">
                {shown.map((d) => (
                  <tr key={d.id} className="hover:bg-surface-container-low">
                    <td className="px-3 py-2">
                      <p className="font-medium">
                        {d.manufacturer || 'Router'} {d.productClass || ''}
                      </p>
                      <p className="text-xs font-mono text-on-surface-variant">{d.serialNumber}</p>
                    </td>
                    <td className="px-3 py-2">{d.softwareVersion || '—'}</td>
                    <td className="px-3 py-2"><StatusPill device={d} /></td>
                    <td className="px-3 py-2 text-right">
                      <button type="button" onClick={() => setOpen(d.id)}
                        className="text-primary text-sm cursor-pointer hover:underline">
                        Open
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {open && <DeviceDetail auth={auth} id={open}
        onClose={() => { setOpen(null); load() }} />}
    </>
  )
}
