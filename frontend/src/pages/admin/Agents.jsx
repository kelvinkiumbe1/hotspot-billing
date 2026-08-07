import { useEffect, useState } from 'react'
import { api } from '../../api.js'
import { Icon, Skeleton, PageHeader, PrimaryButton, StatCard, fmtKES, fmtDate, INPUT_CLS, LABEL_CLS } from '../../components/ui.jsx'

function printBatch(vouchers, label, business) {
  const cards = vouchers.map((v) => `
    <div class="card">
      <div class="head"><strong>${business}</strong><span>INTERNET ACCESS</span></div>
      <div class="code-box"><small>ACCESS CODE</small><div class="code">${v.code}</div></div>
      <div class="foot"><span>${label}</span><span>Use as WiFi username &amp; password</span></div>
    </div>`).join('')
  const w = window.open('', '_blank')
  w.document.write(`<!doctype html><html><head><title>${label}</title><style>
    body { font-family: Arial, sans-serif; margin: 10mm; }
    .grid { display: flex; flex-wrap: wrap; gap: 6mm; }
    .card { width: 85mm; height: 54mm; border: 1px dashed #6e7977; border-top: 3px solid #1a1c1c; border-radius: 4mm;
            padding: 5mm; box-sizing: border-box; display: flex; flex-direction: column; justify-content: space-between;
            page-break-inside: avoid; }
    .head { display: flex; justify-content: space-between; color: #1a1c1c; font-size: 12px; }
    .code-box { text-align: center; border: 1px solid #bdc9c6; border-radius: 2mm; padding: 3mm; }
    .code-box small { color: #6e7977; letter-spacing: 1px; font-size: 9px; }
    .code { font-family: 'Courier New', monospace; font-size: 22px; font-weight: bold; letter-spacing: 3px; }
    .foot { display: flex; justify-content: space-between; font-size: 10px; color: #3e4947; }
  </style></head><body><div class="grid">${cards}</div><script>window.onload = () => window.print()<\/script></body></html>`)
  w.document.close()
}

export default function Agents({ auth }) {
  const [tab, setTab] = useState('agents')
  const [agents, setAgents] = useState(null)
  const [batches, setBatches] = useState(null)
  const [plans, setPlans] = useState([])
  const [business, setBusiness] = useState('SPA WiFi')
  const [showAgentForm, setShowAgentForm] = useState(false)
  const [agentForm, setAgentForm] = useState({ fullName: '', phoneNumber: '', code: '', commissionPercent: 10, location: '' })
  const [batchForm, setBatchForm] = useState({ planId: '', customMinutes: 60, count: 20, prefix: '', codeLength: 8, agentId: '', note: '' })
  const [payFor, setPayFor] = useState(null)
  const [payAmount, setPayAmount] = useState('')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  const loadAgents = () => api('/admin/agents', { auth }).then(setAgents).catch(() => setAgents([]))
  const loadBatches = () => api('/admin/batches', { auth }).then(setBatches).catch(() => setBatches([]))

  useEffect(() => {
    loadAgents()
    loadBatches()
    api('/plans').then((ps) => {
      setPlans(ps)
      if (ps[0]) setBatchForm((f) => ({ ...f, planId: String(ps[0].id) }))
    }).catch(() => {})
    api('/portal-settings').then((s) => setBusiness(s.businessName || 'SPA WiFi')).catch(() => {})
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function createAgent(e) {
    e.preventDefault()
    setBusy(true)
    setMsg(null)
    try {
      await api('/admin/agents', {
        method: 'POST',
        auth,
        body: { ...agentForm, phoneNumber: agentForm.phoneNumber.replace(/\D/g, ''), commissionPercent: Number(agentForm.commissionPercent) },
      })
      setAgentForm({ fullName: '', phoneNumber: '', code: '', commissionPercent: 10, location: '' })
      setShowAgentForm(false)
      loadAgents()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
    }
  }

  async function createBatch(e) {
    e.preventDefault()
    setBusy(true)
    setMsg(null)
    const custom = batchForm.planId === 'custom'
    try {
      const b = await api('/admin/batches', {
        method: 'POST',
        auth,
        body: {
          planId: custom ? null : Number(batchForm.planId),
          customMinutes: custom ? Number(batchForm.customMinutes) : null,
          count: Number(batchForm.count),
          prefix: batchForm.prefix.trim() || null,
          codeLength: Number(batchForm.codeLength) || 8,
          agentId: batchForm.agentId ? Number(batchForm.agentId) : null,
          note: batchForm.note || null,
        },
      })
      setMsg({ ok: true, text: `${b.reference} generated with ${batchForm.count} voucher(s).` })
      loadBatches()
      loadAgents()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
    }
  }

  async function assign(batchId, agentId) {
    await api(`/admin/batches/${batchId}/assign`, { method: 'PATCH', auth, body: { agentId: agentId ? Number(agentId) : null } })
      .catch((e) => setMsg({ ok: false, text: e.message }))
    loadBatches()
    loadAgents()
  }

  async function print(batch) {
    const vouchers = await api(`/admin/batches/${batch.id}/vouchers`, { auth })
    printBatch(vouchers, `${batch.reference} · ${batch.planName}`, business)
  }

  async function payCommission(id) {
    setMsg(null)
    try {
      await api(`/admin/agents/${id}/commission`, { method: 'POST', auth, body: { amount: Number(payAmount) } })
      setPayFor(null)
      setPayAmount('')
      setMsg({ ok: true, text: 'Commission payout recorded.' })
      loadAgents()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    }
  }

  if (agents === null || batches === null) return <Skeleton className="h-64" />

  const totalSold = agents.reduce((a, x) => a + x.sold, 0)
  const totalFace = agents.reduce((a, x) => a + Number(x.faceValue), 0)
  const totalOwed = agents.reduce((a, x) => a + Number(x.commissionOwed), 0)
  const totalStock = agents.reduce((a, x) => a + x.stock, 0)

  return (
    <div>
      <PageHeader title="Agents & Batches" subtitle="Resellers who hold voucher stock, and the batches you print for them." />

      <div className="grid grid-cols-2 md:grid-cols-4 gap-2.5 mb-4">
        <StatCard label="Active Agents" value={agents.filter((a) => a.active).length} hint={agents.length + ' on the books'} accent="border-l-primary" />
        <StatCard label="Vouchers With Agents" value={totalStock} hint="Unused stock in the field" />
        <StatCard label="Sold By Agents" value={totalSold} hint={fmtKES(totalFace) + ' face value'} />
        <StatCard label="Commission Owed" value={fmtKES(totalOwed)} hint="Not yet paid out" accent={totalOwed > 0 ? 'border-l-[#f59e0b]' : ''} />
      </div>

      <nav className="flex gap-2 mb-6 flex-wrap">
        {[['agents', 'Agents'], ['batches', `Batches (${batches.length})`], ['new', 'Generate Batch']].map(([key, label]) => (
          <button key={key} onClick={() => setTab(key)}
            className={`px-4 py-2 rounded-full text-sm transition-colors cursor-pointer ${
              tab === key ? 'bg-primary-container text-on-primary-container font-semibold'
                : 'border border-outline-variant text-on-surface hover:bg-surface-container-high'
            }`}>
            {label}
          </button>
        ))}
      </nav>

      {msg && <p className={`text-sm font-semibold mb-4 ${msg.ok ? 'text-surface-tint' : 'text-error'}`}>{msg.text}</p>}

      {tab === 'agents' && (
        <div>
          <div className="flex justify-end mb-4">
            <PrimaryButton onClick={() => setShowAgentForm(!showAgentForm)}>
              <Icon name="person_add" /> Add Agent
            </PrimaryButton>
          </div>
          {showAgentForm && (
            <form onSubmit={createAgent} className="bg-surface-container-lowest rounded-lg p-4  grid grid-cols-1 md:grid-cols-5 gap-4 items-end mb-6">
              <div>
                <label className={LABEL_CLS}>Full Name</label>
                <input className={INPUT_CLS} required value={agentForm.fullName} onChange={(e) => setAgentForm({ ...agentForm, fullName: e.target.value })} />
              </div>
              <div>
                <label className={LABEL_CLS}>Phone</label>
                <input className={INPUT_CLS} required placeholder="2547XXXXXXXX" value={agentForm.phoneNumber} onChange={(e) => setAgentForm({ ...agentForm, phoneNumber: e.target.value })} />
              </div>
              <div>
                <label className={LABEL_CLS}>Agent Code</label>
                <input className={`${INPUT_CLS} font-mono uppercase`} required placeholder="AG01" value={agentForm.code}
                  onChange={(e) => setAgentForm({ ...agentForm, code: e.target.value.toUpperCase().replace(/[^A-Z0-9-]/g, '') })} />
              </div>
              <div>
                <label className={LABEL_CLS}>Commission %</label>
                <input className={INPUT_CLS} type="number" min="0" max="60" required value={agentForm.commissionPercent}
                  onChange={(e) => setAgentForm({ ...agentForm, commissionPercent: e.target.value })} />
              </div>
              <PrimaryButton type="submit" disabled={busy}>{busy ? 'Saving…' : 'Add'}</PrimaryButton>
            </form>
          )}

          <div className="bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden">
            <div className="overflow-x-auto table-scroll">
              <table className="data-table w-full text-left border-collapse min-w-[950px]">
                <thead>
                  <tr>
                    <th>Agent</th>
                    <th>Code</th>
                    <th>Rate</th>
                    <th>Stock</th>
                    <th>Sold</th>
                    <th>Face Value</th>
                    <th>Commission</th>
                    <th className="text-right">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {agents.map((a) => (
                    <tr key={a.id}>
                      <td>
                        <div className="font-semibold text-on-background">{a.fullName}</div>
                        <div className="text-xs text-on-surface-variant">{a.phoneNumber}{a.location ? ` · ${a.location}` : ''}</div>
                      </td>
                      <td className="font-mono font-semibold">{a.code}</td>
                      <td className="tabular-nums">{a.commissionPercent}%</td>
                      <td className="tabular-nums">{a.stock}</td>
                      <td className="tabular-nums">{a.sold}</td>
                      <td className="tabular-nums whitespace-nowrap">{fmtKES(a.faceValue)}</td>
                      <td className="whitespace-nowrap">
                        <div className="font-semibold tabular-nums">{fmtKES(a.commissionEarned)} earned</div>
                        <div className={`text-xs tabular-nums ${Number(a.commissionOwed) > 0 ? 'text-[#b45309] font-semibold' : 'text-on-surface-variant'}`}>
                          {fmtKES(a.commissionOwed)} owed
                        </div>
                      </td>
                      <td className="text-right">
                        <div className="flex items-center justify-end gap-2 flex-wrap">
                          <button onClick={() => { setPayFor(payFor === a.id ? null : a.id); setPayAmount(String(a.commissionOwed || '')) }}
                            className="px-3 py-1.5 rounded-lg bg-primary text-on-primary text-xs font-semibold cursor-pointer">Pay</button>
                          <button onClick={() => api(`/admin/agents/${a.id}/toggle`, { method: 'PATCH', auth }).then(loadAgents)}
                            className="px-3 py-1.5 rounded-lg border border-outline-variant text-xs font-semibold hover:bg-surface-container transition-colors cursor-pointer">
                            {a.active ? 'Disable' : 'Enable'}
                          </button>
                        </div>
                        {payFor === a.id && (
                          <div className="flex items-center gap-2 mt-2 justify-end">
                            <input type="number" min="1" value={payAmount} onChange={(e) => setPayAmount(e.target.value)}
                              className="h-8 w-24 bg-surface border border-outline-variant rounded-lg px-2 text-xs text-right tabular-nums focus:outline-none focus:border-primary" />
                            <button onClick={() => payCommission(a.id)} className="h-8 px-3 rounded-lg bg-secondary text-on-secondary text-xs font-semibold cursor-pointer">Record</button>
                          </div>
                        )}
                      </td>
                    </tr>
                  ))}
                  {agents.length === 0 && (
                    <tr><td className="text-on-surface-variant" colSpan={8}>No agents yet — add your first reseller.</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {tab === 'batches' && (
        <div className="bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden">
          <div className="overflow-x-auto table-scroll">
            <table className="data-table w-full text-left border-collapse min-w-[950px]">
              <thead>
                <tr>
                  <th>Batch</th>
                  <th>Plan</th>
                  <th>Size</th>
                  <th>Unused</th>
                  <th>Used</th>
                  <th>Held By</th>
                  <th>Created</th>
                  <th className="text-right">Actions</th>
                </tr>
              </thead>
              <tbody>
                {batches.map((b) => (
                  <tr key={b.id}>
                    <td className="font-mono font-semibold">{b.reference}</td>
                    <td>{b.planName}{b.prefix ? <div className="text-xs text-on-surface-variant">prefix {b.prefix}</div> : null}</td>
                    <td className="tabular-nums">{b.count}</td>
                    <td className="tabular-nums">{b.unused}</td>
                    <td className="tabular-nums">{b.used}</td>
                    <td>
                      <select value={b.agentId || ''} onChange={(e) => assign(b.id, e.target.value)}
                        className="h-8 bg-surface border border-outline-variant rounded-lg px-2 text-xs focus:outline-none focus:border-primary cursor-pointer max-w-[170px]">
                        <option value="">Head office</option>
                        {agents.map((a) => <option key={a.id} value={a.id}>{a.fullName} ({a.code})</option>)}
                      </select>
                    </td>
                    <td className="text-on-surface-variant whitespace-nowrap">
                      {fmtDate(b.createdAt)}
                      {b.createdBy && <div className="text-xs capitalize">by {b.createdBy}</div>}
                    </td>
                    <td className="text-right">
                      <button onClick={() => print(b)}
                        className="px-3 py-1.5 rounded-lg border border-primary text-primary text-xs font-semibold hover:bg-primary/5 transition-colors cursor-pointer flex items-center gap-1 ml-auto">
                        <Icon name="print" className="text-[16px]!" /> Print
                      </button>
                    </td>
                  </tr>
                ))}
                {batches.length === 0 && (
                  <tr><td className="text-on-surface-variant" colSpan={8}>No batches yet — generate one on the next tab.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {tab === 'new' && (
        <form onSubmit={createBatch} className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant border-l-2 border-l-primary grid grid-cols-1 md:grid-cols-3 gap-4 items-end max-w-3xl">
          <div>
            <label className={LABEL_CLS}>Plan</label>
            <select className={INPUT_CLS} value={batchForm.planId} onChange={(e) => setBatchForm({ ...batchForm, planId: e.target.value })}>
              {plans.filter((p) => p.name !== 'Custom Time').map((p) => (
                <option key={p.id} value={p.id}>{p.name} — KES {p.price}</option>
              ))}
              <option value="custom">Custom time…</option>
            </select>
          </div>
          {batchForm.planId === 'custom' && (
            <div>
              <label className={LABEL_CLS}>Minutes</label>
              <input className={INPUT_CLS} type="number" min="1" value={batchForm.customMinutes}
                onChange={(e) => setBatchForm({ ...batchForm, customMinutes: e.target.value })} />
            </div>
          )}
          <div>
            <label className={LABEL_CLS}>How Many</label>
            <input className={INPUT_CLS} type="number" min="1" max="500" required value={batchForm.count}
              onChange={(e) => setBatchForm({ ...batchForm, count: e.target.value })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Code Prefix</label>
            <input className={`${INPUT_CLS} font-mono uppercase`} placeholder="e.g. AG01" value={batchForm.prefix}
              onChange={(e) => setBatchForm({ ...batchForm, prefix: e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, '') })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Code Length</label>
            <input className={INPUT_CLS} type="number" min="6" max="16" value={batchForm.codeLength}
              onChange={(e) => setBatchForm({ ...batchForm, codeLength: e.target.value })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Give To Agent</label>
            <select className={INPUT_CLS} value={batchForm.agentId} onChange={(e) => setBatchForm({ ...batchForm, agentId: e.target.value })}>
              <option value="">Head office</option>
              {agents.filter((a) => a.active).map((a) => <option key={a.id} value={a.id}>{a.fullName} ({a.code})</option>)}
            </select>
          </div>
          <div className="md:col-span-2">
            <label className={LABEL_CLS}>Note</label>
            <input className={INPUT_CLS} placeholder="e.g. Weekend stock for Kawangware" value={batchForm.note}
              onChange={(e) => setBatchForm({ ...batchForm, note: e.target.value })} />
          </div>
          <PrimaryButton type="submit" disabled={busy}>
            <Icon name="add_circle" /> {busy ? 'Generating…' : 'Generate Batch'}
          </PrimaryButton>
        </form>
      )}
    </div>
  )
}
