import { useEffect, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, StatCard, relativeTime,
  INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

/**
 * The address blocks, and who holds which address.
 *
 * The API for this has existed for a while and had no screen, which meant a
 * static-IP customer could not actually be set up: the subnet, its gateway and
 * its interface are what turn a reserved address into four values somebody can
 * read down the phone, and none of them could be entered.
 *
 * The gateway and interface are the fields worth caring about here. Without a
 * gateway the customer gets an address and no route; without the interface the
 * address cannot be pinned to their equipment, and the neighbour who types the
 * same address in gets the service instead.
 */

const PURPOSES = [
  ['STATIC', 'Static customers', 'Addresses customers type into their own routers'],
  ['PPPOE', 'PPPoE pool', 'Handed out during dial-in'],
  ['HOTSPOT', 'Hotspot pool', 'Handed out by DHCP to hotspot devices'],
  ['MANAGEMENT', 'Management', 'Routers, OLTs, switches'],
  ['INFRASTRUCTURE', 'Infrastructure', 'Links and transit'],
  ['OTHER', 'Other', ''],
]

const KINDS = [
  ['ASSIGNED', 'A customer'],
  ['GATEWAY', 'The router itself'],
  ['RESERVED', 'Reserved, not in use'],
  ['INFRASTRUCTURE', 'Equipment'],
]

function UsageBar({ percent }) {
  const cls = percent >= 90 ? 'bg-error' : percent >= 75 ? 'bg-warning' : 'bg-secondary'
  return (
    <div className="h-1.5 rounded-full bg-surface-container-high overflow-hidden min-w-[6rem]">
      <div className={`h-full rounded-full ${cls}`} style={{ width: `${Math.max(2, percent)}%` }}></div>
    </div>
  )
}

function SubnetForm({ auth, subnet, routers, onSaved, onCancel }) {
  const [form, setForm] = useState({
    name: subnet?.name || '',
    cidr: subnet?.cidr || '',
    purpose: subnet?.purpose || 'STATIC',
    gateway: subnet?.gateway || '',
    vlanId: subnet?.vlanId ?? '',
    interfaceName: subnet?.interfaceName || '',
    routerId: subnet?.routerId ?? '',
    description: subnet?.description || '',
  })
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))

  async function save() {
    setBusy(true); setMsg(null)
    try {
      const body = {
        ...form,
        vlanId: form.vlanId === '' ? null : Number(form.vlanId),
        routerId: form.routerId === '' ? null : Number(form.routerId),
      }
      if (subnet) await api(`/admin/ipam/subnets/${subnet.id}`, { method: 'PUT', auth, body })
      else await api('/admin/ipam/subnets', { method: 'POST', auth, body })
      onSaved()
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setBusy(false) }
  }

  return (
    <div className="rounded-lg border border-primary p-4 space-y-3">
      <p className="text-sm font-semibold">{subnet ? `Edit ${subnet.name}` : 'New address block'}</p>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <div>
          <label className={LABEL_CLS}>Name</label>
          <input className={INPUT_CLS} value={form.name}
            onChange={(e) => set({ name: e.target.value })} placeholder="Static customers, Westlands" />
        </div>
        <div>
          <label className={LABEL_CLS}>Block</label>
          <input className={`${INPUT_CLS} font-mono`} value={form.cidr} disabled={!!subnet}
            onChange={(e) => set({ cidr: e.target.value })} placeholder="41.90.64.0/26" />
          {subnet && (
            // Changing it would leave every address already handed out sitting
            // outside the block that owns it.
            <p className="text-xs text-on-surface-variant mt-1">
              The block itself cannot be changed once addresses have been handed out of it.
            </p>
          )}
        </div>
        <div className="sm:col-span-2">
          <label className={LABEL_CLS}>What it is for</label>
          <select className={INPUT_CLS} value={form.purpose}
            onChange={(e) => set({ purpose: e.target.value })}>
            {PURPOSES.map(([v, label, hint]) => (
              <option key={v} value={v}>{label}{hint ? ` — ${hint}` : ''}</option>
            ))}
          </select>
        </div>

        {form.purpose === 'STATIC' && (
          <>
            <div>
              <label className={LABEL_CLS}>Gateway</label>
              <input className={`${INPUT_CLS} font-mono`} value={form.gateway}
                onChange={(e) => set({ gateway: e.target.value })} placeholder="41.90.64.1" />
              {/* One of the four values the customer types in. */}
              <p className="text-xs text-on-surface-variant mt-1">
                The router&rsquo;s address in this block. Customers type it in, so without it
                they get an address and no route.
              </p>
            </div>
            <div>
              <label className={LABEL_CLS}>Interface on the router</label>
              <input className={INPUT_CLS} value={form.interfaceName}
                onChange={(e) => set({ interfaceName: e.target.value })} placeholder="bridge-static" />
              <p className="text-xs text-on-surface-variant mt-1">
                Where these customers connect &mdash; a bridge, a VLAN or a port. Needed to
                pin each address to the customer&rsquo;s own equipment.
              </p>
            </div>
          </>
        )}

        <div>
          <label className={LABEL_CLS}>VLAN (optional)</label>
          <input className={INPUT_CLS} type="number" value={form.vlanId}
            onChange={(e) => set({ vlanId: e.target.value })} />
        </div>
        <div>
          <label className={LABEL_CLS}>Router / site</label>
          <select className={INPUT_CLS} value={form.routerId}
            onChange={(e) => set({ routerId: e.target.value })}>
            <option value="">Not tied to one</option>
            {routers.map((r) => <option key={r.id} value={String(r.id)}>{r.name}</option>)}
          </select>
        </div>
        <div className="sm:col-span-2">
          <label className={LABEL_CLS}>Note</label>
          <input className={INPUT_CLS} value={form.description}
            onChange={(e) => set({ description: e.target.value })} />
        </div>
      </div>

      <div className="flex gap-2">
        <PrimaryButton disabled={busy || !form.name || !form.cidr} onClick={save}>
          {busy ? 'Saving…' : 'Save'}
        </PrimaryButton>
        <button type="button" onClick={onCancel}
          className="px-4 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high">
          Cancel
        </button>
      </div>
      {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-error'}`}>{msg.text}</p>}
    </div>
  )
}

function SubnetDetail({ auth, subnetId, customers, onClose, onChanged }) {
  const [data, setData] = useState(null)
  const [form, setForm] = useState({ address: '', kind: 'ASSIGNED', subscriberId: '', hostname: '', macAddress: '', notes: '' })
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  const load = () => api(`/admin/ipam/subnets/${subnetId}`, { auth })
    .then(setData).catch(() => setData(null))
  useEffect(() => { load() }, [subnetId]) // eslint-disable-line react-hooks/exhaustive-deps

  async function assign() {
    setBusy(true); setMsg(null)
    try {
      const r = await api(`/admin/ipam/subnets/${subnetId}/assign`, {
        method: 'POST', auth,
        body: {
          address: form.address.trim() || null,
          kind: form.kind,
          subscriberId: form.subscriberId ? Number(form.subscriberId) : null,
          hostname: form.hostname.trim() || null,
          macAddress: form.macAddress.trim() || null,
          notes: form.notes.trim() || null,
        },
      })
      setMsg({ ok: true, text: `${r.address} taken.` })
      setForm({ ...form, address: '', subscriberId: '', hostname: '', macAddress: '', notes: '' })
      load(); onChanged()
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setBusy(false) }
  }

  async function release(id, address) {
    if (!window.confirm(`Release ${address}? It becomes available for somebody else.`)) return
    try {
      await api(`/admin/ipam/assignments/${id}`, { method: 'DELETE', auth })
      load(); onChanged()
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    }
  }

  if (!data) return <Skeleton className="h-64" />

  return (
    <div className="fixed inset-0 bg-black/40 flex items-start justify-center p-4 z-50 overflow-y-auto">
      <div className="bg-surface rounded-xl w-full max-w-2xl my-8 p-5 space-y-4">
        <div className="flex items-start justify-between gap-4">
          <div>
            <p className="text-lg font-semibold">{data.name}</p>
            <p className="text-xs font-mono text-on-surface-variant">
              {data.cidr} · {data.firstUsable} – {data.lastUsable}
              {data.gateway ? ` · gateway ${data.gateway}` : ''}
            </p>
          </div>
          <button type="button" onClick={onClose} className="cursor-pointer"><Icon name="close" /></button>
        </div>

        <div className="grid grid-cols-3 gap-3">
          <StatCard label="Addresses" value={data.usable} />
          <StatCard label="Taken" value={data.used} />
          <StatCard label="Free" value={data.free}
            accent={data.free === 0 ? 'border-t-error' : undefined} />
        </div>

        <div className="rounded-lg border border-outline-variant p-3 space-y-3">
          <p className="text-sm font-semibold">
            Hand out an address
            {data.nextFree && (
              <span className="font-normal text-on-surface-variant">
                {' '}— next free is <span className="font-mono">{data.nextFree}</span>
              </span>
            )}
          </p>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <label className={LABEL_CLS}>Address (blank = next free)</label>
              <input className={`${INPUT_CLS} font-mono`} value={form.address}
                placeholder={data.nextFree || ''}
                onChange={(e) => setForm({ ...form, address: e.target.value })} />
            </div>
            <div>
              <label className={LABEL_CLS}>For</label>
              <select className={INPUT_CLS} value={form.kind}
                onChange={(e) => setForm({ ...form, kind: e.target.value })}>
                {KINDS.map(([v, label]) => <option key={v} value={v}>{label}</option>)}
              </select>
            </div>
            {form.kind === 'ASSIGNED' && (
              <>
                <div>
                  <label className={LABEL_CLS}>Customer</label>
                  <select className={INPUT_CLS} value={form.subscriberId}
                    onChange={(e) => setForm({ ...form, subscriberId: e.target.value })}>
                    <option value="">Choose one</option>
                    {customers.map((c) => (
                      <option key={c.id} value={String(c.id)}>{c.fullName} ({c.pppoeUsername})</option>
                    ))}
                  </select>
                  {/* Assigning to a customer mirrors the address onto their
                      record, which is what the static provisioning reads. */}
                  <p className="text-xs text-on-surface-variant mt-1">
                    This becomes their static address, and their connection settings follow
                    from this block.
                  </p>
                </div>
                <div>
                  <label className={LABEL_CLS}>Their device address (MAC)</label>
                  <input className={`${INPUT_CLS} font-mono`} value={form.macAddress}
                    placeholder="AA:BB:CC:DD:EE:FF"
                    onChange={(e) => setForm({ ...form, macAddress: e.target.value })} />
                </div>
              </>
            )}
            {form.kind !== 'ASSIGNED' && (
              <div className="sm:col-span-2">
                <label className={LABEL_CLS}>What it is</label>
                <input className={INPUT_CLS} value={form.hostname}
                  placeholder="core router, OLT, uplink"
                  onChange={(e) => setForm({ ...form, hostname: e.target.value })} />
              </div>
            )}
          </div>
          <PrimaryButton disabled={busy || (form.kind === 'ASSIGNED' && !form.subscriberId)}
            onClick={assign}>
            {busy ? 'Taking…' : 'Take it'}
          </PrimaryButton>
          {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-error'}`}>{msg.text}</p>}
        </div>

        {data.assignments.length > 0 && (
          <div className="overflow-x-auto rounded-lg border border-outline-variant">
            <table className="w-full text-sm">
              <thead className="bg-surface-container-low text-on-surface-variant">
                <tr>
                  <th className="text-left font-medium px-3 py-2">Address</th>
                  <th className="text-left font-medium px-3 py-2">Held by</th>
                  <th className="text-left font-medium px-3 py-2">Since</th>
                  <th className="px-3 py-2"></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-outline-variant/40">
                {data.assignments.map((a) => (
                  <tr key={a.id} className="hover:bg-surface-container-low">
                    <td className="px-3 py-2 font-mono">{a.address}</td>
                    <td className="px-3 py-2">
                      <p>{a.subscriberName || a.hostname || a.kind.toLowerCase()}</p>
                      {a.macAddress && (
                        <p className="text-xs font-mono text-on-surface-variant">{a.macAddress}</p>
                      )}
                    </td>
                    <td className="px-3 py-2 text-on-surface-variant">
                      {a.assignedAt ? relativeTime(a.assignedAt) : '—'}
                    </td>
                    <td className="px-3 py-2 text-right">
                      {a.kind === 'GATEWAY' ? (
                        // Releasing it would let it be handed to a customer and
                        // take the site down.
                        <span className="text-xs text-on-surface-variant">the router</span>
                      ) : (
                        <button type="button" onClick={() => release(a.id, a.address)}
                          className="text-error text-sm cursor-pointer hover:underline">Release</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  )
}

export default function AddressesPage({ auth }) {
  const [subnets, setSubnets] = useState(null)
  const [routers, setRouters] = useState([])
  const [customers, setCustomers] = useState([])
  const [editing, setEditing] = useState(null)
  const [open, setOpen] = useState(null)

  const load = () => api('/admin/ipam', { auth })
    .then((d) => setSubnets(Array.isArray(d) ? d : (d?.subnets || [])))
    .catch(() => setSubnets([]))
  useEffect(() => {
    load()
    api('/admin/routers', { auth }).then((d) => setRouters(d || [])).catch(() => {})
    api('/admin/subscribers', { auth }).then((d) => setCustomers(d || [])).catch(() => {})
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!subnets) return <Skeleton className="h-64" />

  const tight = subnets.filter((s) => s.percentUsed >= 90).length

  return (
    <>
      <PageHeader title="Addresses"
        subtitle="Your address blocks, and who holds which one.">
        <PrimaryButton onClick={() => setEditing({})}>New block</PrimaryButton>
      </PageHeader>

      <div className="space-y-4">
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <StatCard label="Blocks" value={subnets.length} />
          <StatCard label="Addresses" value={subnets.reduce((s, x) => s + x.usable, 0)} />
          <StatCard label="Taken" value={subnets.reduce((s, x) => s + x.used, 0)} />
          <StatCard label="Nearly full" value={tight}
            accent={tight > 0 ? 'border-t-error' : undefined} />
        </div>

        {editing && (
          <SubnetForm auth={auth} subnet={editing.id ? editing : null} routers={routers}
            onSaved={() => { setEditing(null); load() }} onCancel={() => setEditing(null)} />
        )}

        {subnets.length === 0 ? (
          <div className="rounded-lg border border-outline-variant p-6 text-center">
            <Icon name="lan" className="text-[32px]! text-on-surface-variant" />
            <p className="text-base font-semibold mt-2">No address blocks yet</p>
            <p className="text-sm text-on-surface-variant mt-1 max-w-lg mx-auto">
              Add one for your static customers, with its gateway and the interface they
              connect on. Those are what turn a reserved address into settings somebody can
              read down the phone.
            </p>
          </div>
        ) : (
          <div className="overflow-x-auto rounded-lg border border-outline-variant">
            <table className="w-full text-sm">
              <thead className="bg-surface-container-low text-on-surface-variant">
                <tr>
                  <th className="text-left font-medium px-3 py-2">Block</th>
                  <th className="text-left font-medium px-3 py-2">For</th>
                  <th className="text-left font-medium px-3 py-2">Gateway</th>
                  <th className="text-left font-medium px-3 py-2">Used</th>
                  <th className="px-3 py-2"></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-outline-variant/40">
                {subnets.map((s) => (
                  <tr key={s.id} className="hover:bg-surface-container-low">
                    <td className="px-3 py-2">
                      <p className="font-medium">{s.name}</p>
                      <p className="text-xs font-mono text-on-surface-variant">{s.cidr}</p>
                    </td>
                    <td className="px-3 py-2 text-on-surface-variant">
                      {(PURPOSES.find((p) => p[0] === s.purpose) || [, s.purpose])[1]}
                      {s.interfaceName && (
                        <span className="block text-xs font-mono">{s.interfaceName}</span>
                      )}
                    </td>
                    <td className="px-3 py-2 font-mono text-xs">
                      {s.gateway || (
                        s.purpose === 'STATIC'
                          // A static block without one is unusable, and the
                          // symptom is a customer with an address and no route.
                          ? <span className="text-warning font-sans">none set</span>
                          : '—'
                      )}
                    </td>
                    <td className="px-3 py-2">
                      <UsageBar percent={s.percentUsed} />
                      <p className="text-xs text-on-surface-variant mt-1">
                        {s.used} of {s.usable}
                      </p>
                    </td>
                    <td className="px-3 py-2 text-right whitespace-nowrap">
                      <button type="button" onClick={() => setOpen(s.id)}
                        className="text-primary text-sm cursor-pointer hover:underline">Open</button>
                      <button type="button" onClick={() => setEditing(s)}
                        className="ml-3 text-on-surface-variant text-sm cursor-pointer hover:underline">
                        Edit
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {open && <SubnetDetail auth={auth} subnetId={open} customers={customers}
        onClose={() => setOpen(null)} onChanged={load} />}
    </>
  )
}
