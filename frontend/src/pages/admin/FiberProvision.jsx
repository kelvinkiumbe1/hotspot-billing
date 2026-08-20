import { useEffect, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PrimaryButton, INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

/**
 * Authorising an ONU, with the commands shown first.
 *
 * The only screen in this admin that can darken a street. So it is deliberately
 * two steps: pressing Authorise shows the exact commands and sends nothing, and a
 * second, separate press runs them. The commands are sent back to the server
 * unchanged so what runs is what was read.
 *
 * That is not ceremony. None of these commands has been run against a real OLT of
 * any make -- there is no test OLT anywhere -- so the operator reading them is the
 * last check there is.
 */

function CommandList({ commands }) {
  return (
    <pre className="text-xs font-mono bg-surface-container-high rounded-lg p-3 overflow-x-auto whitespace-pre">
      {commands.join('\n')}
    </pre>
  )
}

export default function FiberProvision({ auth }) {
  const [olts, setOlts] = useState(null)
  const [oltId, setOltId] = useState('')
  const [found, setFound] = useState(null)
  const [scanning, setScanning] = useState(false)
  const [msg, setMsg] = useState(null)

  // What the operator is about to do, once, and only after they have seen it.
  const [pending, setPending] = useState(null)
  const [applying, setApplying] = useState(false)
  const [outcome, setOutcome] = useState(null)

  useEffect(() => {
    api('/admin/devices', { auth })
      .then((d) => {
        const list = (d.devices || []).filter((x) => x.kind === 'OLT')
        setOlts(list)
        if (list.length === 1) setOltId(String(list[0].id))
      })
      .catch(() => setOlts([]))
  }, [auth])

  async function scan() {
    if (!oltId) return
    setScanning(true); setMsg(null); setFound(null); setPending(null); setOutcome(null)
    try {
      const d = await api(`/admin/olt/${oltId}/unregistered`, { auth })
      setFound(d.onus || [])
      if ((d.onus || []).length === 0) {
        setMsg({
          ok: true,
          // Both possibilities, because they are indistinguishable from here.
          text: 'Nothing waiting — either every ONU is authorised, or the OLT did not '
            + 'answer the way we expected. Check the vendor is set correctly on the device.',
        })
      }
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setScanning(false) }
  }

  async function preview(onu, action) {
    setMsg(null); setOutcome(null)
    const placement = {
      serial: onu.serial,
      frame: onu.frame || '0',
      slot: onu.slot || '0',
      port: onu.port || '0',
      onuId: onu.onuId || '',
      name: onu.name || '',
      lineProfile: onu.lineProfile || '',
      srvProfile: onu.srvProfile || '',
    }
    try {
      const plan = await api(`/admin/olt/${oltId}/preview/${action}`, {
        method: 'POST', auth, body: placement,
      })
      setPending({ action, placement, plan })
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    }
  }

  async function apply() {
    if (!pending?.plan?.possible) return
    setApplying(true); setMsg(null)
    try {
      // The commands go back exactly as shown. The server checks them against a
      // fresh preview, so a stale screen cannot run something else.
      const r = await api(`/admin/olt/${oltId}/apply`, {
        method: 'POST', auth,
        body: {
          action: pending.action,
          placement: pending.placement,
          commands: pending.plan.commands,
        },
      })
      setOutcome(r)
      if (r.ok) { setPending(null); scan() }
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setApplying(false) }
  }

  if (olts === null) return <Skeleton className="h-64" />
  if (olts.length === 0) {
    return (
      <div className="rounded-lg border border-outline-variant p-6 text-center">
        <Icon name="settings_input_antenna" className="text-[32px]! text-on-surface-variant" />
        <p className="text-base font-semibold mt-2">No OLT is set up yet</p>
        <p className="text-sm text-on-surface-variant mt-1">
          Add one under Devices with its type set to OLT, then set the vendor and the
          telnet login.
        </p>
      </div>
    )
  }

  return (
    <div className="space-y-5">
      <div className="rounded-lg border border-warning/40 bg-warning-container p-3">
        <p className="text-sm text-on-warning-container flex items-start gap-2">
          <Icon name="warning" className="text-[18px]! mt-0.5" />
          <span>
            These commands go straight to the OLT. A wrong one can take a whole PON port
            down. Nothing is sent until you have seen it &mdash; read the commands before
            you apply them.
          </span>
        </p>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <div>
          <label className={LABEL_CLS}>OLT</label>
          <select className={INPUT_CLS} value={oltId} onChange={(e) => setOltId(e.target.value)}>
            <option value="">Choose one</option>
            {olts.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
          </select>
        </div>
        <PrimaryButton disabled={!oltId || scanning} onClick={scan}>
          {scanning ? 'Looking…' : 'Find waiting ONUs'}
        </PrimaryButton>
      </div>

      {msg && <p className={`text-sm ${msg.ok ? 'text-on-surface-variant' : 'text-error'}`}>{msg.text}</p>}

      {found && found.length > 0 && (
        <div className="space-y-3">
          <p className="text-sm font-semibold">{found.length} ONU(s) waiting to be authorised</p>
          {found.map((onu, i) => <WaitingOnu key={onu.serial + i} onu={onu} onPreview={preview} />)}
        </div>
      )}

      {pending && (
        <div className="rounded-lg border-2 border-primary p-4 space-y-3">
          <div>
            <p className="text-base font-semibold">
              About to {pending.action} {pending.placement.serial}
            </p>
            <p className="text-sm text-on-surface-variant">
              This is exactly what will be typed at the OLT. Nothing has been sent yet.
            </p>
          </div>
          {pending.plan.possible ? (
            <>
              <CommandList commands={pending.plan.commands} />
              <p className="text-xs text-warning">{pending.plan.warning}</p>
              <div className="flex gap-2">
                <PrimaryButton disabled={applying} onClick={apply}>
                  {applying ? 'Sending…' : 'Send these commands'}
                </PrimaryButton>
                <button type="button" onClick={() => setPending(null)}
                  className="px-4 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high">
                  Cancel
                </button>
              </div>
            </>
          ) : (
            <p className="text-sm text-error">{pending.plan.reason}</p>
          )}
        </div>
      )}

      {outcome && (
        <div className={`rounded-lg border p-4 space-y-2 ${
          outcome.ok ? 'border-secondary' : 'border-error'}`}>
          <p className="text-sm font-semibold">
            {outcome.ok ? 'Done' : 'The OLT refused it'}
          </p>
          <p className="text-sm">{outcome.detail}</p>
          {outcome.transcript && outcome.transcript.length > 0 && (
            <details>
              <summary className="cursor-pointer text-sm text-primary">
                What was said, both ways
              </summary>
              {/* The whole conversation. On a rail with no sandbox this is the
                  only way to tell a wrong command from an unreachable box. */}
              <CommandList commands={outcome.transcript} />
            </details>
          )}
        </div>
      )}
    </div>
  )
}

/** One waiting ONU, with the few things the OLT cannot work out for itself. */
function WaitingOnu({ onu, onPreview }) {
  const [extra, setExtra] = useState({
    onuId: '', name: '', lineProfile: '', srvProfile: '',
    frame: onu.frame || '0', slot: onu.slot || '0', port: onu.port || '0',
  })
  const set = (patch) => setExtra((e) => ({ ...e, ...patch }))

  return (
    <div className="rounded-lg border border-outline-variant p-3 space-y-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="font-mono text-sm font-medium">{onu.serial}</p>
          {/* The OLT's own line. This parse is guesswork on output nobody has
              verified, so showing what was read lets an operator judge it. */}
          <p className="text-xs text-on-surface-variant font-mono truncate">{onu.raw}</p>
        </div>
      </div>

      <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
        {[['frame', 'Frame'], ['slot', 'Slot'], ['port', 'Port'], ['onuId', 'ONU id']].map(
          ([key, label]) => (
            <div key={key}>
              <label className={LABEL_CLS}>{label}</label>
              <input className={INPUT_CLS} value={extra[key]}
                placeholder={key === 'onuId' ? 'next free' : ''}
                onChange={(e) => set({ [key]: e.target.value })} />
            </div>
          ))}
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
        <div className="sm:col-span-1">
          <label className={LABEL_CLS}>Customer / label</label>
          <input className={INPUT_CLS} value={extra.name}
            placeholder="House 12" onChange={(e) => set({ name: e.target.value })} />
        </div>
        <div>
          <label className={LABEL_CLS}>Line profile</label>
          <input className={INPUT_CLS} value={extra.lineProfile}
            placeholder="1" onChange={(e) => set({ lineProfile: e.target.value })} />
        </div>
        <div>
          <label className={LABEL_CLS}>Service profile</label>
          <input className={INPUT_CLS} value={extra.srvProfile}
            placeholder="1" onChange={(e) => set({ srvProfile: e.target.value })} />
        </div>
      </div>

      <button type="button" onClick={() => onPreview({ ...onu, ...extra }, 'authorise')}
        className="px-4 py-2 rounded-lg border border-primary text-primary text-sm font-semibold cursor-pointer hover:bg-primary/5">
        Show me the commands
      </button>
    </div>
  )
}
