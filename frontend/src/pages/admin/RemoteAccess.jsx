import { useEffect, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, StatCard, Toggle, relativeTime,
  INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

/**
 * The tunnel routers dial out to.
 *
 * This page exists because of a failure that never announces itself: a router on
 * a mobile or domestic line has no address anything can dial in to, so every
 * "apply now" in this admin quietly becomes "apply whenever the box next checks
 * in", and the monitor calls it offline while it works perfectly.
 *
 * The screen is built around the one step people forget. Setting the tunnel up on
 * a router is a button; adding that router's peer line on the SERVER is a copy and
 * paste somebody has to do by hand, and until they do the tunnel cannot come up.
 * So a router that is set up and has never been seen working is called out
 * explicitly rather than left looking broken.
 */

function CopyBox({ text, label }) {
  const [copied, setCopied] = useState(false)
  if (!text) return null
  return (
    <div className="space-y-1">
      {label && <p className="text-xs font-semibold text-on-surface-variant">{label}</p>}
      <div className="flex gap-2 items-start">
        <pre className="flex-1 min-w-0 text-xs font-mono bg-surface-container-high rounded-lg px-3 py-2 overflow-x-auto whitespace-pre">{text}</pre>
        <button type="button"
          onClick={() => { navigator.clipboard?.writeText(text); setCopied(true); setTimeout(() => setCopied(false), 1500) }}
          className="px-3 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high whitespace-nowrap">
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
    </div>
  )
}

/** Where one router's tunnel stands. */
function TunnelState({ row }) {
  if (!row.vpnAddress) {
    return (
      <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium bg-surface-container-high text-on-surface-variant">
        <span className="w-1.5 h-1.5 rounded-full bg-outline"></span>no tunnel
      </span>
    )
  }
  if (row.awaitingPeer) {
    // The one manual step, called out by name. This is the state almost every
    // "the tunnel does not work" turns out to be.
    return (
      <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium bg-warning-container text-on-warning-container">
        <span className="w-1.5 h-1.5 rounded-full bg-warning"></span>waiting for its peer line
      </span>
    )
  }
  const stale = row.lastOkAt && Date.now() - new Date(row.lastOkAt).getTime() > 6 * 3600000
  return (
    <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium ${
      stale ? 'bg-warning-container text-on-warning-container'
        : 'bg-secondary-container text-on-secondary-container'}`}>
      <span className={`w-1.5 h-1.5 rounded-full ${stale ? 'bg-warning' : 'bg-secondary'}`}></span>
      {row.lastOkAt ? `last worked ${relativeTime(row.lastOkAt)}` : 'up'}
    </span>
  )
}

function Setup({ data, auth, onSaved }) {
  const [form, setForm] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  useEffect(() => {
    setForm({
      enabled: data.enabled,
      serverPublicKey: data.serverPublicKey || '',
      endpoint: data.endpoint || '',
      subnet: data.subnet,
      serverAddress: data.serverAddress,
      keepaliveSeconds: data.keepaliveSeconds,
      interfaceName: data.interfaceName,
    })
  }, [data])
  if (!form) return <Skeleton className="h-48" />
  const set = (patch) => setForm({ ...form, ...patch })

  async function save() {
    setBusy(true); setMsg(null)
    try {
      await api('/admin/vpn/settings', {
        method: 'PUT', auth,
        body: { ...form, keepaliveSeconds: Number(form.keepaliveSeconds) || 25 },
      })
      setMsg({ ok: true, text: 'Saved.' })
      onSaved()
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setBusy(false) }
  }

  return (
    <div className="space-y-4">
      <div className="rounded-lg border border-outline-variant p-4 space-y-4">
        <div className="flex items-start justify-between gap-4">
          <div>
            <p className="text-sm font-semibold">The tunnel</p>
            <p className="text-xs text-on-surface-variant mt-1">
              Run <code className="font-mono">sudo ./deploy/vpn-setup.sh</code> on the server
              first. It prints everything below.
            </p>
          </div>
          <Toggle checked={form.enabled} onChange={(v) => set({ enabled: v })} />
        </div>

        {data.whyNotUsable && (
          <p className="text-sm text-warning flex items-start gap-2">
            <Icon name="warning" className="text-[18px]! mt-0.5" />{data.whyNotUsable}
          </p>
        )}

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <div className="sm:col-span-2">
            <label className={LABEL_CLS}>Server public key</label>
            <input className={`${INPUT_CLS} font-mono text-xs`} value={form.serverPublicKey}
              placeholder="the key vpn-setup.sh printed"
              onChange={(e) => set({ serverPublicKey: e.target.value })} />
            {/* Worth saying, because "where do I put the private key" is the
                first thing anybody asks. */}
            <p className="text-xs text-on-surface-variant mt-1">
              Public half only. No private key is ever stored here or on the
              routers&rsquo; behalf &mdash; each router makes its own and keeps it.
            </p>
          </div>
          <div className="sm:col-span-2">
            <label className={LABEL_CLS}>Endpoint routers dial</label>
            <input className={INPUT_CLS} value={form.endpoint} placeholder="vpn.example.net:13231"
              onChange={(e) => set({ endpoint: e.target.value })} />
            <p className="text-xs text-on-surface-variant mt-1">
              Must be reachable from the outside &mdash; this end is the one that has to be.
            </p>
          </div>
          <div>
            <label className={LABEL_CLS}>Tunnel subnet</label>
            <input className={INPUT_CLS} value={form.subnet}
              onChange={(e) => set({ subnet: e.target.value })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Our address on it</label>
            <input className={INPUT_CLS} value={form.serverAddress}
              onChange={(e) => set({ serverAddress: e.target.value })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Keepalive (seconds)</label>
            <input className={INPUT_CLS} type="number" min="10" max="180"
              value={form.keepaliveSeconds}
              onChange={(e) => set({ keepaliveSeconds: e.target.value })} />
            <p className="text-xs text-on-surface-variant mt-1">
              The tunnel rests on a NAT mapping that expires in silence. Without
              keepalives it goes one-way and still looks up from the router.
            </p>
          </div>
          <div>
            <label className={LABEL_CLS}>Interface name on routers</label>
            <input className={INPUT_CLS} value={form.interfaceName}
              onChange={(e) => set({ interfaceName: e.target.value })} />
          </div>
        </div>

        <PrimaryButton disabled={busy} onClick={save}>
          {busy ? 'Saving…' : 'Save'}
        </PrimaryButton>
        {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-error'}`}>{msg.text}</p>}
      </div>
    </div>
  )
}

export default function RemoteAccessPage({ auth }) {
  const [data, setData] = useState(null)
  const [tab, setTab] = useState('routers')
  const [busy, setBusy] = useState(null)
  const [result, setResult] = useState(null)
  const [script, setScript] = useState(null)
  const [allPeers, setAllPeers] = useState(null)

  const load = () => api('/admin/vpn', { auth }).then(setData).catch(() => setData({ routers: [] }))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function act(routerId, what) {
    setBusy(routerId + what); setResult(null); setScript(null)
    try {
      const r = what === 'script'
        ? await api(`/admin/vpn/${routerId}/script`, { auth })
        : await api(`/admin/vpn/${routerId}/${what}`, { method: 'POST', auth })
      if (what === 'script') setScript({ routerId, commands: r.commands })
      else { setResult({ routerId, ...r }); load() }
    } catch (e) {
      setResult({ routerId, ok: false, message: e.message })
    } finally { setBusy(null) }
  }

  if (!data) return <Skeleton className="h-64" />

  const rows = data.routers || []
  const onTunnel = rows.filter((r) => r.vpnAddress).length
  const waiting = rows.filter((r) => r.awaitingPeer).length

  return (
    <>
      <PageHeader title="Remote access"
        subtitle="A tunnel each router dials out to, so one behind carrier NAT can still be reached." />

      <div className="space-y-4">
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <StatCard label="Routers" value={rows.length} />
          <StatCard label="On the tunnel" value={onTunnel} />
          <StatCard label="Waiting for a peer line" value={waiting}
            accent={waiting > 0 ? 'border-t-error' : undefined} />
          <StatCard label="Tunnel" value={data.usable ? 'ready' : 'not set up'} />
        </div>

        <div className="flex gap-1 border-b border-outline-variant">
          {[['routers', 'Routers', 'router'], ['setup', 'Setup', 'settings']].map(([k, label, icon]) => (
            <button key={k} type="button" onClick={() => setTab(k)}
              className={`px-4 py-2 text-sm font-medium cursor-pointer flex items-center gap-2 border-b-2 ${
                tab === k ? 'border-primary text-primary'
                  : 'border-transparent text-on-surface-variant hover:text-on-surface'}`}>
              <Icon name={icon} className="text-[18px]!" />{label}
            </button>
          ))}
        </div>

        {tab === 'setup' && <Setup data={data} auth={auth} onSaved={load} />}

        {tab === 'routers' && (
          <div className="space-y-4">
            {!data.usable && (
              <div className="rounded-lg border border-warning/40 bg-warning/10 p-3">
                <p className="text-sm text-warning flex items-start gap-2">
                  <Icon name="info" className="text-[18px]! mt-0.5" />
                  <span>
                    <strong>The tunnel is not set up yet.</strong> {data.whyNotUsable}{' '}
                    <button type="button" onClick={() => setTab('setup')}
                      className="underline cursor-pointer">Open setup</button>.
                  </span>
                </p>
              </div>
            )}

            {waiting > 0 && (
              <div className="rounded-lg border border-warning/40 bg-warning/10 p-3 space-y-2">
                <p className="text-sm text-on-warning-container">
                  <strong>{waiting} router{waiting === 1 ? '' : 's'} set up but never seen
                  working.</strong> That is almost always the peer line not being added on the
                  server yet. Paste the block below into
                  {' '}<code className="font-mono">/etc/wireguard/{data.interfaceName}.conf</code>{' '}
                  and run <code className="font-mono">wg syncconf {data.interfaceName} &lt;(wg-quick strip {data.interfaceName})</code>.
                </p>
                <button type="button"
                  onClick={() => api('/admin/vpn/peers', { auth }).then((r) => setAllPeers(r.config))}
                  className="text-sm text-primary cursor-pointer hover:underline">
                  Show every peer line
                </button>
                {allPeers && <CopyBox text={allPeers} />}
              </div>
            )}

            {rows.length === 0 ? (
              <div className="rounded-lg border border-outline-variant p-6 text-center">
                <Icon name="vpn_key" className="text-[32px]! text-on-surface-variant" />
                <p className="text-base font-semibold mt-2">No routers yet</p>
                <p className="text-sm text-on-surface-variant mt-1">Add one under Routers first.</p>
              </div>
            ) : (
              <div className="overflow-x-auto rounded-lg border border-outline-variant">
                <table className="w-full text-sm">
                  <thead className="bg-surface-container-low text-on-surface-variant">
                    <tr>
                      <th className="text-left font-medium px-3 py-2">Router</th>
                      <th className="text-left font-medium px-3 py-2">Tunnel address</th>
                      <th className="text-left font-medium px-3 py-2">State</th>
                      <th className="px-3 py-2"></th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-outline-variant/40">
                    {rows.map((r) => (
                      <tr key={r.routerId} className="hover:bg-surface-container-low align-top">
                        <td className="px-3 py-2">
                          <p className="font-medium">{r.name}</p>
                          <p className="text-xs font-mono text-on-surface-variant">{r.host}</p>
                        </td>
                        <td className="px-3 py-2 font-mono text-xs">{r.vpnAddress || '—'}</td>
                        <td className="px-3 py-2">
                          <TunnelState row={r} />
                          {r.error && <p className="text-xs text-error mt-0.5">{r.error}</p>}
                        </td>
                        <td className="px-3 py-2 text-right whitespace-nowrap">
                          <button type="button" disabled={busy === r.routerId + 'configure' || !data.usable}
                            onClick={() => act(r.routerId, 'configure')}
                            className="text-primary text-sm cursor-pointer hover:underline disabled:opacity-50">
                            {busy === r.routerId + 'configure' ? 'Setting up…' : 'Set up tunnel'}
                          </button>
                          {r.vpnAddress && (
                            <button type="button" disabled={busy === r.routerId + 'check'}
                              onClick={() => act(r.routerId, 'check')}
                              className="ml-3 text-primary text-sm cursor-pointer hover:underline">
                              Test
                            </button>
                          )}
                          <button type="button" onClick={() => act(r.routerId, 'script')}
                            className="ml-3 text-on-surface-variant text-sm cursor-pointer hover:underline">
                            Commands
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {result && (
              <div className={`rounded-lg border p-4 space-y-3 ${
                result.ok ? 'border-secondary' : 'border-error'}`}>
                <p className="text-sm">{result.message}</p>
                {result.peerStanza && (
                  <CopyBox label="Add this to the server, then reload WireGuard"
                    text={result.peerStanza} />
                )}
              </div>
            )}

            {script && (
              <div className="rounded-lg border border-outline-variant p-4 space-y-2">
                <p className="text-sm font-semibold">Paste these at the router&rsquo;s console</p>
                {/* The router that most needs a tunnel is the one already
                    unreachable, and that one cannot be set up over the API. */}
                <p className="text-xs text-on-surface-variant">
                  For a router that is already unreachable &mdash; the one that needs this most,
                  and the one the button above cannot help with.
                </p>
                <CopyBox text={script.commands.join('\n')} />
              </div>
            )}
          </div>
        )}

        <p className="text-xs text-on-surface-variant flex items-start gap-2">
          <Icon name="info" className="text-[16px]! mt-0.5" />
          Once a router is on the tunnel, everything else in this admin prefers it
          automatically and falls back to the public address if it is down &mdash; so a
          broken tunnel never takes a reachable router with it.
        </p>
      </div>
    </>
  )
}
