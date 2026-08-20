import { useEffect, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, StatCard, relativeTime, INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

const KINDS = [
  ['SWITCH', 'Switch', 'lan'],
  ['ACCESS_POINT', 'Access point', 'wifi'],
  ['ONT', 'ONT', 'settings_input_hdmi'],
  ['UPS', 'UPS', 'battery_charging_full'],
  ['SERVER', 'Server', 'dns'],
  ['ROUTER', 'Router', 'router'],
  ['OLT', 'OLT (fibre)', 'settings_input_antenna'],
  ['OTHER', 'Other', 'device_hub'],
]

const OLT_VENDORS = ['HUAWEI', 'ZTE', 'VSOL', 'BDCOM', 'FIBERHOME']

const AUTH_PROTOCOLS = ['NONE', 'MD5', 'SHA1', 'SHA224', 'SHA256', 'SHA384', 'SHA512']
const PRIV_PROTOCOLS = ['NONE', 'DES', 'TRIPLE_DES', 'AES128', 'AES192', 'AES256']

const kindIcon = (kind) => (KINDS.find((k) => k[0] === kind) || KINDS.at(-1))[2]
const kindLabel = (kind) => (KINDS.find((k) => k[0] === kind) || KINDS.at(-1))[1]

/** Bits per second, at whatever scale keeps it to three digits. */
function bps(value) {
  if (value === null || value === undefined) return '—'
  if (value >= 1e9) return `${(value / 1e9).toFixed(2)} Gbps`
  if (value >= 1e6) return `${(value / 1e6).toFixed(1)} Mbps`
  if (value >= 1e3) return `${Math.round(value / 1e3)} kbps`
  return `${value} bps`
}

function uptime(seconds) {
  if (!seconds) return '—'
  const d = Math.floor(seconds / 86400)
  const h = Math.floor((seconds % 86400) / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  if (d > 0) return `${d}d ${h}h`
  return h > 0 ? `${h}h ${m}m` : `${m}m`
}

function DeviceModal({ auth, device, branches, onClose, onSaved }) {
  const editing = !!device
  const [form, setForm] = useState({
    name: device?.name || '',
    kind: device?.kind || 'SWITCH',
    host: device?.host || '',
    port: device?.port || 161,
    location: device?.location || '',
    branchId: device?.branchId || '',
    enabled: device ? device.enabled : true,
    snmpVersion: device?.snmpVersion || 'V2C',
    community: '',
    securityName: device?.securityName || '',
    authProtocol: device?.authProtocol || 'SHA1',
    authPassphrase: '',
    privProtocol: device?.privProtocol || 'AES128',
    privPassphrase: '',
    notes: device?.notes || '',
    // OLT only. Blank means "use the vendor preset" everywhere here, which is
    // why none of these is defaulted to anything.
    oltVendor: device?.oltVendor || '',
    onuSerialOid: device?.onuSerialOid || '',
    onuRxPowerOid: device?.onuRxPowerOid || '',
    onuTxPowerOid: device?.onuTxPowerOid || '',
    onuStatusOid: device?.onuStatusOid || '',
    onuPowerUnit: device?.onuPowerUnit || '',
    onuPowerScale: device?.onuPowerScale ?? '',
    cliUsername: device?.cliUsername || '',
    cliPassword: '',
    cliPort: device?.cliPort || 23,
  })
  // What a vendor's preset actually is, so the OID boxes can show it as their
  // placeholder rather than leaving an operator to guess what blank means.
  const [presets, setPresets] = useState({})
  useEffect(() => {
    api('/admin/devices/olt-presets', { auth })
      .then((d) => setPresets(d.presets || {}))
      .catch(() => {})
  }, [auth])
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))
  const isV3 = form.snmpVersion === 'V3'

  async function save(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api(editing ? `/admin/devices/${device.id}` : '/admin/devices', {
        method: editing ? 'PUT' : 'POST',
        auth,
        body: {
          ...form,
          port: Number(form.port) || 161,
          branchId: form.branchId ? Number(form.branchId) : null,
        },
      })
      onSaved()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="fixed inset-0 bg-on-background/50 backdrop-blur-sm z-50 flex items-center justify-center p-5"
      onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="bg-surface-container-lowest w-full max-w-lg rounded-xl shadow-[0_8px_24px_rgba(15,23,42,0.15)]">
        <div className="p-6 border-b border-outline-variant/50 flex justify-between items-center">
          <h3 className="text-2xl font-bold text-on-background">{editing ? 'Edit device' : 'Add device'}</h3>
          <button onClick={onClose} aria-label="Close"
            className="text-on-surface-variant hover:text-error transition-colors p-1 rounded-full hover:bg-error/10 cursor-pointer">
            <Icon name="close" />
          </button>
        </div>
        <form onSubmit={save}>
          <div className="p-6 space-y-4 max-h-[65vh] overflow-y-auto">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className={LABEL_CLS}>Name</label>
                <input className={INPUT_CLS} required placeholder="e.g. Cabinet switch"
                  value={form.name} onChange={(e) => set({ name: e.target.value })} />
              </div>
              <div>
                <label className={LABEL_CLS}>What it is</label>
                <select className={INPUT_CLS} value={form.kind} onChange={(e) => set({ kind: e.target.value })}>
                  {KINDS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
                </select>
              </div>
            </div>

            <div className="grid grid-cols-3 gap-4">
              <div className="col-span-2">
                <label className={LABEL_CLS}>IP address / host</label>
                <input className={INPUT_CLS} required placeholder="192.168.88.10"
                  value={form.host} onChange={(e) => set({ host: e.target.value })} />
              </div>
              <div>
                <label className={LABEL_CLS}>SNMP port</label>
                <input className={INPUT_CLS} type="number" value={form.port}
                  onChange={(e) => set({ port: e.target.value })} />
              </div>
            </div>

            <div>
              <label className={LABEL_CLS}>Location</label>
              <input className={INPUT_CLS} placeholder="e.g. Westlands cabinet, shelf 2"
                value={form.location} onChange={(e) => set({ location: e.target.value })} />
            </div>

            {branches.length > 0 && (
              <div>
                <label className={LABEL_CLS}>Branch</label>
                <select className={INPUT_CLS} value={form.branchId}
                  onChange={(e) => set({ branchId: e.target.value })}>
                  <option value="">Head office</option>
                  {branches.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
                </select>
              </div>
            )}

            <div>
              <label className={LABEL_CLS}>SNMP version</label>
              <div className="flex gap-2">
                {['V1', 'V2C', 'V3'].map((v) => (
                  <button key={v} type="button" onClick={() => set({ snmpVersion: v })}
                    aria-pressed={form.snmpVersion === v}
                    className={`px-4 py-2 rounded-full text-sm cursor-pointer transition-colors ${
                      form.snmpVersion === v
                        ? 'bg-primary-container text-on-primary-container font-semibold'
                        : 'border border-outline-variant hover:bg-surface-container-high'
                    }`}>
                    {v === 'V2C' ? 'v2c' : v.toLowerCase()}
                  </button>
                ))}
              </div>
              {!isV3 && (
                <p className="text-xs text-[#b45309] mt-1.5">
                  v1 and v2c send the community string in clear text on every poll, every five minutes.
                  Fine on a management VLAN you trust; v3 is the one that doesn't.
                </p>
              )}
            </div>

            {isV3 ? (
              <>
                <div>
                  <label className={LABEL_CLS}>Username</label>
                  <input className={INPUT_CLS} value={form.securityName}
                    onChange={(e) => set({ securityName: e.target.value })} placeholder="the SNMPv3 user" />
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className={LABEL_CLS}>Auth</label>
                    <select className={INPUT_CLS} value={form.authProtocol}
                      onChange={(e) => set({ authProtocol: e.target.value })}>
                      {AUTH_PROTOCOLS.map((p) => <option key={p} value={p}>{p === 'NONE' ? 'None' : p}</option>)}
                    </select>
                  </div>
                  <div>
                    <label className={LABEL_CLS}>
                      Auth passphrase {editing && <span className="normal-case font-normal">(blank = keep)</span>}
                    </label>
                    <input className={INPUT_CLS} type="password" value={form.authPassphrase}
                      onChange={(e) => set({ authPassphrase: e.target.value })}
                      placeholder={device?.hasAuthPassphrase ? '••••••••' : ''} />
                  </div>
                  <div>
                    <label className={LABEL_CLS}>Privacy</label>
                    <select className={INPUT_CLS} value={form.privProtocol}
                      onChange={(e) => set({ privProtocol: e.target.value })}>
                      {PRIV_PROTOCOLS.map((p) => <option key={p} value={p}>{p === 'NONE' ? 'None' : p}</option>)}
                    </select>
                  </div>
                  <div>
                    <label className={LABEL_CLS}>
                      Privacy passphrase {editing && <span className="normal-case font-normal">(blank = keep)</span>}
                    </label>
                    <input className={INPUT_CLS} type="password" value={form.privPassphrase}
                      onChange={(e) => set({ privPassphrase: e.target.value })}
                      placeholder={device?.hasPrivPassphrase ? '••••••••' : ''} />
                  </div>
                </div>
              </>
            ) : (
              <div>
                <label className={LABEL_CLS}>
                  Community string {editing && <span className="normal-case font-normal">(blank = keep)</span>}
                </label>
                <input className={INPUT_CLS} type="password" value={form.community}
                  onChange={(e) => set({ community: e.target.value })}
                  placeholder={device?.hasCommunity ? '••••••••' : 'usually "public" until someone changes it'} />
              </div>
            )}

            {form.kind === 'OLT' && (
              <div className="rounded-lg border border-outline-variant p-4 space-y-4">
                <div>
                  <p className="text-sm font-semibold">Fibre</p>
                  <p className="text-xs text-on-surface-variant mt-1">
                    Where to find the ONUs on this OLT, and how to log in to provision them.
                    Leave the OID boxes blank to use the vendor&rsquo;s defaults.
                  </p>
                </div>

                <div>
                  <label className={LABEL_CLS}>Vendor</label>
                  <select className={INPUT_CLS} value={form.oltVendor}
                    onChange={(e) => set({ oltVendor: e.target.value })}>
                    <option value="">Choose one — nothing works without it</option>
                    {OLT_VENDORS.map((v) => <option key={v} value={v}>{v}</option>)}
                  </select>
                  <p className="text-xs text-on-surface-variant mt-1">
                    Vendors share nothing here: the ONU tables and the provisioning commands
                    are different on every one.
                  </p>
                </div>

                {form.oltVendor && (
                  <>
                    <details className="text-sm">
                      <summary className="cursor-pointer text-primary">
                        ONU table addresses (only if the defaults don&rsquo;t work)
                      </summary>
                      <div className="mt-3 space-y-3">
                        <p className="text-xs text-[#b45309]">
                          These defaults have never been checked against a real
                          {' '}{form.oltVendor} OLT &mdash; there is no test OLT anywhere. If the
                          light readings come back empty, run <code>snmpwalk</code> against your
                          own box, find the right column, and put it here.
                        </p>
                        {[
                          ['onuSerialOid', 'Serial number column', 'serial'],
                          ['onuRxPowerOid', 'Receive power column', 'rxPower'],
                          ['onuTxPowerOid', 'Transmit power column', 'txPower'],
                          ['onuStatusOid', 'Status column', 'status'],
                        ].map(([field, label, presetKey]) => (
                          <div key={field}>
                            <label className={LABEL_CLS}>{label}</label>
                            <input className={`${INPUT_CLS} font-mono text-xs`}
                              value={form[field]}
                              placeholder={presets[form.oltVendor]?.[presetKey] || ''}
                              onChange={(e) => set({ [field]: e.target.value })} />
                          </div>
                        ))}
                        <div className="grid grid-cols-2 gap-3">
                          <div>
                            <label className={LABEL_CLS}>Power is reported as</label>
                            <select className={INPUT_CLS} value={form.onuPowerUnit}
                              onChange={(e) => set({ onuPowerUnit: e.target.value })}>
                              <option value="">
                                {presets[form.oltVendor]?.unit || 'the vendor default'}
                              </option>
                              <option value="DBM_SCALED">Fixed-point dBm</option>
                              <option value="MICROWATT">Microwatts</option>
                            </select>
                          </div>
                          <div>
                            <label className={LABEL_CLS}>Divide by</label>
                            <input className={INPUT_CLS} type="number" value={form.onuPowerScale}
                              placeholder={presets[form.oltVendor]?.scale ?? '100'}
                              onChange={(e) => set({ onuPowerScale: e.target.value })} />
                            <p className="text-xs text-on-surface-variant mt-1">
                              100 for hundredths of a dBm. Wrong here and a healthy
                              &minus;24.6 reads as &minus;2456.
                            </p>
                          </div>
                        </div>
                      </div>
                    </details>

                    <div className="grid grid-cols-3 gap-3">
                      <div>
                        <label className={LABEL_CLS}>CLI username</label>
                        <input className={INPUT_CLS} value={form.cliUsername}
                          onChange={(e) => set({ cliUsername: e.target.value })} />
                      </div>
                      <div>
                        <label className={LABEL_CLS}>
                          CLI password
                          {device?.cliUsername && (
                            <span className="normal-case font-normal"> (blank = keep)</span>
                          )}
                        </label>
                        <input className={INPUT_CLS} type="password" value={form.cliPassword}
                          onChange={(e) => set({ cliPassword: e.target.value })} />
                      </div>
                      <div>
                        <label className={LABEL_CLS}>Telnet port</label>
                        <input className={INPUT_CLS} type="number" value={form.cliPort}
                          onChange={(e) => set({ cliPort: e.target.value })} />
                      </div>
                    </div>
                    <p className="text-xs text-[#b45309] flex items-start gap-2">
                      <Icon name="warning" className="text-[16px]! mt-0.5" />
                      This is telnet, so the password crosses your network in the clear. Keep
                      the OLT on a management VLAN that customers cannot reach.
                    </p>
                  </>
                )}
              </div>
            )}

            <div>
              <label className={LABEL_CLS}>Notes</label>

              <textarea className={`${INPUT_CLS} min-h-[60px]`} value={form.notes}
                onChange={(e) => set({ notes: e.target.value })}
                placeholder="Anything the next person on call needs to know." />
            </div>

            <label className="flex items-center gap-3 p-3 border border-outline-variant rounded-lg cursor-pointer hover:bg-surface-container-low transition-colors w-fit">
              <input type="checkbox" checked={form.enabled}
                onChange={(e) => set({ enabled: e.target.checked })}
                className="w-4 h-4 accent-[#fdbf2d]" />
              <span className="text-sm text-on-surface">Poll this device</span>
            </label>

            {error && <p className="text-sm text-error">{error}</p>}
          </div>
          <div className="p-6 border-t border-outline-variant/50 bg-surface-container/30 flex justify-end gap-3 rounded-b-xl">
            <button type="button" onClick={onClose}
              className="px-4 h-10 rounded-md text-sm font-semibold border border-primary text-primary hover:bg-primary/5 transition-colors min-h-[40px] cursor-pointer">
              Cancel
            </button>
            <PrimaryButton type="submit" disabled={busy}>
              {busy ? 'Saving…' : editing ? 'Save changes' : 'Add device'}
            </PrimaryButton>
          </div>
        </form>
      </div>
    </div>
  )
}

/** The port list. Where the useful signal is, so it gets the room. */
function PortsPanel({ auth, device, onClose }) {
  const [data, setData] = useState(null)
  const load = () => api(`/admin/devices/${device.id}`, { auth }).then(setData).catch(() => setData(null))
  useEffect(() => { load() }, [device.id, auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function toggleWatch(port) {
    await api(`/admin/devices/ports/${port.id}/watch`, {
      method: 'PUT', auth, body: { monitored: !port.monitored },
    })
    load()
  }

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="flex-1 bg-on-background/30 backdrop-blur-[2px]" onClick={onClose}></div>
      <div className="w-full max-w-2xl bg-surface-container-lowest h-full shadow-[0_8px_24px_rgba(15,23,42,0.15)] flex flex-col">
        <div className="p-6 border-b border-outline-variant bg-surface-container-low flex justify-between items-start">
          <div className="min-w-0">
            <h3 className="text-lg font-semibold text-on-surface">{device.name}</h3>
            <p className="text-sm text-on-surface-variant mt-1 truncate">
              {device.sysName ? `${device.sysName} · ` : ''}{device.host}
              {device.uptimeSeconds ? ` · up ${uptime(device.uptimeSeconds)}` : ''}
            </p>
          </div>
          <button onClick={onClose} aria-label="Close"
            className="w-8 h-8 rounded-full hover:bg-surface-container flex items-center justify-center text-on-surface-variant cursor-pointer">
            <Icon name="close" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto">
          {data === null ? <Skeleton className="h-32 m-4" /> : (
            <>
              {data.sysDescr && (
                <p className="px-6 py-3 text-xs text-on-surface-variant border-b border-outline-variant/50">
                  {data.sysDescr}
                </p>
              )}
              {data.lastRebootAt && (
                <p className="px-6 py-2 text-xs text-[#b45309] border-b border-outline-variant/50">
                  Last seen to restart {relativeTime(data.lastRebootAt)}.
                </p>
              )}
              {data.ports.length === 0 ? (
                <p className="p-6 text-sm text-on-surface-variant">
                  No ports read yet. Use “Check now” — if it stays empty, the device answers its name
                  but not its interface table, which some very cut-down firmware does.
                </p>
              ) : (
                <table className="w-full text-sm">
                  <thead className="text-xs uppercase tracking-wider text-on-surface-variant bg-surface-container-low">
                    <tr>
                      <th className="text-left px-4 py-2 font-semibold">Port</th>
                      <th className="text-left px-2 py-2 font-semibold">In</th>
                      <th className="text-left px-2 py-2 font-semibold">Out</th>
                      <th className="text-left px-2 py-2 font-semibold">Use</th>
                      <th className="text-left px-2 py-2 font-semibold">Errors</th>
                      <th className="text-right px-4 py-2 font-semibold">Watch</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-[color:var(--color-outline-variant)]">
                    {data.ports.map((p) => {
                      const errors = (p.inErrors || 0) + (p.outErrors || 0)
                      return (
                        <tr key={p.id} className={p.operUp ? '' : 'opacity-60'}>
                          <td className="px-4 py-2">
                            <div className="flex items-center gap-2">
                              <span className={`w-2 h-2 rounded-full shrink-0 ${
                                p.operUp ? 'bg-primary' : p.adminUp ? 'bg-error' : 'bg-outline-variant'
                              }`}></span>
                              <div className="min-w-0">
                                <p className="font-medium truncate">{p.label}</p>
                                <p className="text-xs text-on-surface-variant">
                                  {p.operUp ? bps(p.speedBps) : p.adminUp ? 'no link' : 'switched off'}
                                </p>
                              </div>
                            </div>
                          </td>
                          <td className="px-2 py-2 whitespace-nowrap">{bps(p.inBps)}</td>
                          <td className="px-2 py-2 whitespace-nowrap">{bps(p.outBps)}</td>
                          <td className="px-2 py-2">
                            {p.utilisation === null || p.utilisation === undefined ? '—' : (
                              <span className={p.utilisation >= 80 ? 'text-error font-semibold' : ''}>
                                {p.utilisation}%
                              </span>
                            )}
                          </td>
                          <td className="px-2 py-2">
                            {errors > 0
                              ? <span className="text-error font-semibold">{errors}</span>
                              : <span className="text-on-surface-variant">0</span>}
                          </td>
                          <td className="px-4 py-2 text-right">
                            <button onClick={() => toggleWatch(p)}
                              title={p.monitored ? 'Stop alerting if this port drops' : 'Alert me if this port drops'}
                              className="p-1.5 rounded-md hover:bg-surface-container-high cursor-pointer">
                              <Icon name={p.monitored ? 'notifications_active' : 'notifications_off'}
                                className={`text-[18px]! ${p.monitored ? 'text-primary' : 'text-on-surface-variant'}`} />
                            </button>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              )}
              <p className="p-4 text-xs text-on-surface-variant">
                Watching a port means an SMS when it drops. Left off by default — being paged for every
                unused access port is how the alerts that matter get ignored.
              </p>
            </>
          )}
        </div>
      </div>
    </div>
  )
}

export default function Devices({ auth }) {
  const [data, setData] = useState(null)
  const [branches, setBranches] = useState([])
  const [modal, setModal] = useState(null)
  const [portsFor, setPortsFor] = useState(null)
  const [msg, setMsg] = useState(null)
  const [checking, setChecking] = useState(null)
  const [confirmDelete, setConfirmDelete] = useState(null)

  const load = () => api('/admin/devices', { auth }).then(setData).catch(() => setData({ devices: [] }))
  useEffect(() => {
    load()
    api('/admin/branches', { auth }).then(setBranches).catch(() => {})
    const t = setInterval(load, 60000)
    return () => clearInterval(t)
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function check(d) {
    setChecking(d.id)
    setMsg(null)
    try {
      const r = await api(`/admin/devices/${d.id}/check`, { method: 'POST', auth })
      setMsg({ ok: r.online, text: `${d.name}: ${r.message}` })
    } catch (err) {
      setMsg({ ok: false, text: `${d.name}: ${err.message}` })
    } finally {
      setChecking(null)
      load()
    }
  }

  async function remove(d) {
    try {
      await api(`/admin/devices/${d.id}`, { method: 'DELETE', auth })
      setMsg({ ok: true, text: `${d.name} removed.` })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    }
    setConfirmDelete(null)
    load()
  }

  if (data === null) return <Skeleton className="h-64" />

  const devices = data.devices || []

  return (
    <div>
      <PageHeader
        title="Devices"
        subtitle="The switches, antennas, ONTs and UPSes behind the routers — the gear whose failures a router never reports."
      >
        <PrimaryButton onClick={() => setModal({})}>
          <Icon name="add" className="text-[18px]!" /> Add device
        </PrimaryButton>
      </PageHeader>

      {devices.length > 0 && (
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
          <StatCard label="Devices" value={data.total} />
          <StatCard label="Not answering" value={data.offline}
            accent={data.offline > 0 ? 'border-t-[color:var(--color-error)]' : undefined}
            hint={data.offline > 0 ? 'a switch down looks like nothing from the router' : undefined} />
          <StatCard label="Ports watched"
            value={devices.reduce((a, d) => a + (d.portsWatched || 0), 0)}
            hint="ports that raise an alert if they drop" />
          <StatCard label="Credentials in clear" value={data.inClear}
            hint={data.inClear > 0 ? 'v1/v2c community strings, sent unencrypted' : 'all on SNMPv3'} />
        </div>
      )}

      {msg && (
        <p className={`mb-4 text-sm ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>
      )}

      {devices.length === 0 ? (
        <div className="bg-surface-container-lowest rounded-lg p-8 border border-outline-variant/40 text-center">
          <Icon name="device_hub" className="text-[40px]! text-on-surface-variant" />
          <p className="mt-3 font-semibold">Nothing but routers is being watched</p>
          <p className="text-sm text-on-surface-variant mt-1 max-w-lg mx-auto">
            A switch that drops half its ports leaves the router perfectly healthy, so the first report
            comes from a customer. Add the switches, antennas and ONTs and they get watched too — they
            already speak SNMP, nobody has been asking.
          </p>
          <div className="mt-4">
            <PrimaryButton onClick={() => setModal({})}>Add the first device</PrimaryButton>
          </div>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {devices.map((d) => (
            <div key={d.id}
              className={`bg-surface-container-lowest rounded-lg p-4 border transition-colors ${
                !d.enabled ? 'border-outline-variant/40 opacity-60'
                  : d.online ? 'border-outline-variant/40' : 'border-error'
              }`}>
              <div className="flex items-start gap-3">
                <span className="w-11 h-11 rounded-lg bg-surface-container-high flex items-center justify-center shrink-0">
                  <Icon name={kindIcon(d.kind)} className="text-[22px]!" />
                </span>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <h3 className="font-bold truncate">{d.name}</h3>
                    {!d.enabled ? (
                      <span className="px-2 py-0.5 rounded-full bg-surface-container-high text-[10px] font-bold tracking-wider">
                        NOT POLLED
                      </span>
                    ) : (
                      <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold tracking-wider ${
                        d.online ? 'bg-primary text-on-primary' : 'bg-error text-on-error'
                      }`}>
                        {d.online ? 'ANSWERING' : 'NOT ANSWERING'}
                      </span>
                    )}
                  </div>
                  <p className="text-xs text-on-surface-variant truncate">
                    {kindLabel(d.kind)} · {d.host}{d.location ? ` · ${d.location}` : ''}
                  </p>
                </div>
              </div>

              {d.online ? (
                <div className="grid grid-cols-3 gap-2 mt-3 text-sm">
                  <div>
                    <p className="text-xs text-on-surface-variant">Ports up</p>
                    <p className="font-semibold">{d.portsUp} / {d.portsTotal}</p>
                  </div>
                  <div>
                    <p className="text-xs text-on-surface-variant">Uptime</p>
                    <p className="font-semibold">{uptime(d.uptimeSeconds)}</p>
                  </div>
                  <div>
                    <p className="text-xs text-on-surface-variant">Watched</p>
                    <p className="font-semibold">{d.portsWatched}</p>
                  </div>
                </div>
              ) : d.lastError ? (
                <p className="mt-3 text-sm text-error">{d.lastError}</p>
              ) : (
                <p className="mt-3 text-sm text-on-surface-variant">Not polled yet.</p>
              )}

              <div className="flex items-center justify-between gap-3 mt-4 pt-3 border-t border-outline-variant/50">
                <span className="text-xs text-on-surface-variant">
                  {d.lastCheckedAt ? `checked ${relativeTime(d.lastCheckedAt)}` : 'never checked'}
                  {d.credentialInClear && d.enabled && ' · credential in clear'}
                </span>
                <div className="flex gap-1">
                  <button onClick={() => check(d)} disabled={checking === d.id} title="Poll it now"
                    className="px-3 py-1.5 rounded-md border border-outline-variant text-xs font-semibold hover:bg-surface-container-high transition-colors cursor-pointer disabled:opacity-50">
                    {checking === d.id ? 'Checking…' : 'Check now'}
                  </button>
                  <button onClick={() => setPortsFor(d)} title="Ports"
                    className="p-1.5 rounded-md hover:bg-surface-container-high cursor-pointer">
                    <Icon name="lan" className="text-[18px]!" />
                  </button>
                  <button onClick={() => setModal({ device: d })} title="Edit"
                    className="p-1.5 rounded-md hover:bg-surface-container-high cursor-pointer">
                    <Icon name="edit" className="text-[18px]!" />
                  </button>
                  <button onClick={() => setConfirmDelete(d)} title="Remove"
                    className="p-1.5 rounded-md hover:bg-error/10 text-on-surface-variant hover:text-error cursor-pointer">
                    <Icon name="delete" className="text-[18px]!" />
                  </button>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {modal && (
        <DeviceModal auth={auth} device={modal.device} branches={branches}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null)
            setMsg({ ok: true, text: 'Saved. Use “Check now” to confirm it answers.' })
            load()
          }} />
      )}

      {portsFor && <PortsPanel auth={auth} device={portsFor} onClose={() => setPortsFor(null)} />}

      {confirmDelete && (
        <div className="fixed inset-0 bg-on-background/50 backdrop-blur-sm z-50 flex items-center justify-center p-5"
          onClick={(e) => e.target === e.currentTarget && setConfirmDelete(null)}>
          <div className="bg-surface-container-lowest w-full max-w-sm rounded-xl p-6">
            <h3 className="text-lg font-bold">Remove {confirmDelete.name}?</h3>
            <p className="text-sm text-on-surface-variant mt-2">
              Its port history goes with it. The device itself is untouched — it just stops being watched.
            </p>
            <div className="flex justify-end gap-3 mt-5">
              <button onClick={() => setConfirmDelete(null)}
                className="px-4 h-10 rounded-md text-sm font-semibold border border-outline-variant cursor-pointer hover:bg-surface-container-high">
                Cancel
              </button>
              <button onClick={() => remove(confirmDelete)}
                className="px-4 h-10 rounded-md text-sm font-semibold bg-error text-on-error cursor-pointer hover:opacity-90">
                Remove
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
