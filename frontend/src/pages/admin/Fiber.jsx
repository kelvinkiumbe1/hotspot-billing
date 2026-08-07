import { useEffect, useMemo, useRef, useState } from 'react'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, StatCard, INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

// Nairobi, so an empty map still opens somewhere useful.
const DEFAULT_CENTRE = [-1.2864, 36.8172]
const DEFAULT_ZOOM = 13

const KINDS = [
  { key: 'POP', label: 'POP', hint: 'Point of presence — where our fibre starts.' },
  { key: 'OLT', label: 'OLT', hint: 'The line terminal serving a set of splitters.' },
  { key: 'CLOSURE', label: 'Closure', hint: 'A joint where cables are spliced.' },
  { key: 'SPLITTER', label: 'Splitter', hint: 'Splits one fibre into many legs.' },
  { key: 'ODB', label: 'ODB', hint: 'Distribution box customers are patched into.' },
  { key: 'DROP', label: 'Drop', hint: "The run to a customer's wall." },
]

const NODE_STATUSES = ['PLANNED', 'ACTIVE', 'FAULT', 'DECOMMISSIONED']
const ROUTE_KINDS = ['BACKBONE', 'DISTRIBUTION', 'DROP']

// Status carries the colour; the shape carries the kind, so neither is
// doing the job alone.
const STATUS_COLOUR = {
  ACTIVE: '#0f766e',
  PLANNED: '#94a3b8',
  FAULT: '#dc2626',
  DECOMMISSIONED: '#475569',
}

const KIND_SHAPE = {
  POP: 'square',
  OLT: 'ring',
  CLOSURE: 'circle',
  SPLITTER: 'triangle',
  ODB: 'diamond',
  DROP: 'dot',
}

const pretty = (s) => (s || '').replace(/_/g, ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase())

/**
 * A node marker drawn as inline SVG: the shape says what it is, the fill
 * says how it is doing. Built as a divIcon so it scales crisply and needs
 * no external image files.
 */
function nodeIcon(node) {
  const colour = STATUS_COLOUR[node.status] || '#64748b'
  const shape = KIND_SHAPE[node.kind] || 'circle'
  const size = shape === 'dot' ? 12 : 18
  let inner
  switch (shape) {
    case 'square':
      inner = `<rect x="2" y="2" width="14" height="14" rx="2" fill="${colour}" stroke="#fff" stroke-width="2"/>`
      break
    case 'ring':
      inner = `<circle cx="9" cy="9" r="7" fill="#fff" stroke="${colour}" stroke-width="4"/>`
      break
    case 'triangle':
      inner = `<polygon points="9,1 17,16 1,16" fill="${colour}" stroke="#fff" stroke-width="1.5"/>`
      break
    case 'diamond':
      inner = `<polygon points="9,1 17,9 9,17 1,9" fill="${colour}" stroke="#fff" stroke-width="1.5"/>`
      break
    case 'dot':
      inner = `<circle cx="6" cy="6" r="5" fill="${colour}" stroke="#fff" stroke-width="1.5"/>`
      break
    default:
      inner = `<circle cx="9" cy="9" r="7" fill="${colour}" stroke="#fff" stroke-width="2"/>`
  }
  return L.divIcon({
    className: 'fiber-node-marker',
    html: `<svg width="${size}" height="${size}" viewBox="0 0 ${size} ${size}">${inner}</svg>`,
    iconSize: [size, size],
    iconAnchor: [size / 2, size / 2],
  })
}

/** Legend swatch reusing the same SVG rules as the map itself. */
function LegendSwatch({ kind }) {
  const html = nodeIcon({ kind, status: 'ACTIVE' }).options.html
  return <span className="inline-flex w-5 justify-center" dangerouslySetInnerHTML={{ __html: html }} />
}

function NodeForm({ nodes, subscribers, routers, draft, onCancel, onSave }) {
  const [form, setForm] = useState({
    name: '',
    kind: 'ODB',
    status: 'PLANNED',
    latitude: draft ? draft.lat.toFixed(6) : '',
    longitude: draft ? draft.lng.toFixed(6) : '',
    capacity: '',
    used: '',
    parentId: '',
    subscriberId: '',
    routerId: '',
    address: '',
    notes: '',
  })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))

  const tracksPorts = ['OLT', 'SPLITTER', 'ODB'].includes(form.kind)

  async function submit(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await onSave({
        name: form.name,
        kind: form.kind,
        status: form.status,
        latitude: Number(form.latitude),
        longitude: Number(form.longitude),
        capacity: tracksPorts && form.capacity ? Number(form.capacity) : null,
        used: tracksPorts && form.used ? Number(form.used) : null,
        parentId: form.parentId ? Number(form.parentId) : null,
        subscriberId: form.subscriberId ? Number(form.subscriberId) : null,
        routerId: form.routerId ? Number(form.routerId) : null,
        address: form.address || null,
        notes: form.notes || null,
      })
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="space-y-4">
      <div className="grid grid-cols-2 gap-3">
        <div className="col-span-2">
          <label className={LABEL_CLS}>Name</label>
          <input className={INPUT_CLS} required placeholder="e.g. ODB Kileleshwa 3"
            value={form.name} onChange={(e) => set({ name: e.target.value })} />
        </div>
        <div>
          <label className={LABEL_CLS}>Type</label>
          <select className={INPUT_CLS} value={form.kind} onChange={(e) => set({ kind: e.target.value })}>
            {KINDS.map((k) => <option key={k.key} value={k.key}>{k.label}</option>)}
          </select>
        </div>
        <div>
          <label className={LABEL_CLS}>Status</label>
          <select className={INPUT_CLS} value={form.status} onChange={(e) => set({ status: e.target.value })}>
            {NODE_STATUSES.map((s) => <option key={s} value={s}>{pretty(s)}</option>)}
          </select>
        </div>
      </div>

      <p className="text-xs text-on-surface-variant">{KINDS.find((k) => k.key === form.kind)?.hint}</p>

      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className={LABEL_CLS}>Latitude</label>
          <input className={INPUT_CLS} required type="number" step="any" min="-90" max="90"
            value={form.latitude} onChange={(e) => set({ latitude: e.target.value })} />
        </div>
        <div>
          <label className={LABEL_CLS}>Longitude</label>
          <input className={INPUT_CLS} required type="number" step="any" min="-180" max="180"
            value={form.longitude} onChange={(e) => set({ longitude: e.target.value })} />
        </div>
      </div>
      {draft && <p className="text-xs text-primary">Taken from where you clicked the map.</p>}

      {tracksPorts && (
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className={LABEL_CLS}>Ports</label>
            <input className={INPUT_CLS} type="number" min="0" value={form.capacity}
              onChange={(e) => set({ capacity: e.target.value })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Used</label>
            <input className={INPUT_CLS} type="number" min="0" value={form.used}
              onChange={(e) => set({ used: e.target.value })} />
          </div>
        </div>
      )}

      <div>
        <label className={LABEL_CLS}>Feeds from</label>
        <select className={INPUT_CLS} value={form.parentId} onChange={(e) => set({ parentId: e.target.value })}>
          <option value="">Nothing upstream</option>
          {nodes.map((n) => <option key={n.id} value={n.id}>{n.name} ({n.kind})</option>)}
        </select>
      </div>

      {form.kind === 'DROP' && (
        <div>
          <label className={LABEL_CLS}>Serves subscriber</label>
          <select className={INPUT_CLS} value={form.subscriberId} onChange={(e) => set({ subscriberId: e.target.value })}>
            <option value="">Not linked</option>
            {subscribers.map((s) => (
              <option key={s.id} value={s.id}>{s.fullName} · {s.pppoeUsername}</option>
            ))}
          </select>
        </div>
      )}

      <div>
        <label className={LABEL_CLS}>Served by router</label>
        <select className={INPUT_CLS} value={form.routerId} onChange={(e) => set({ routerId: e.target.value })}>
          <option value="">Not linked</option>
          {routers.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
        </select>
      </div>

      <div>
        <label className={LABEL_CLS}>Notes</label>
        <input className={INPUT_CLS} value={form.notes} onChange={(e) => set({ notes: e.target.value })} />
      </div>

      {error && <p className="text-sm text-error">{error}</p>}

      <div className="flex gap-2">
        <PrimaryButton type="submit" disabled={busy}>{busy ? 'Saving…' : 'Add node'}</PrimaryButton>
        <button type="button" onClick={onCancel}
          className="px-4 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high">
          Cancel
        </button>
      </div>
    </form>
  )
}

function RouteForm({ nodes, onCancel, onSave }) {
  const [form, setForm] = useState({
    fromNodeId: '', toNodeId: '', kind: 'DISTRIBUTION', status: 'PLANNED',
    cores: '', lengthMeters: '', name: '', notes: '',
  })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))

  const sameEnds = form.fromNodeId && form.fromNodeId === form.toNodeId

  async function submit(e) {
    e.preventDefault()
    if (sameEnds) {
      setError('A route needs two different ends.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      await onSave({
        name: form.name || null,
        kind: form.kind,
        status: form.status,
        fromNodeId: Number(form.fromNodeId),
        toNodeId: Number(form.toNodeId),
        cores: form.cores ? Number(form.cores) : null,
        lengthMeters: form.lengthMeters ? Number(form.lengthMeters) : null,
        notes: form.notes || null,
      })
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  if (nodes.length < 2) {
    return (
      <div>
        <p className="text-sm text-on-surface-variant">
          Add at least two nodes before joining them with a cable run.
        </p>
        <button onClick={onCancel} className="mt-3 px-4 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer">
          Back
        </button>
      </div>
    )
  }

  return (
    <form onSubmit={submit} className="space-y-4">
      <div>
        <label className={LABEL_CLS}>From</label>
        <select className={INPUT_CLS} required value={form.fromNodeId} onChange={(e) => set({ fromNodeId: e.target.value })}>
          <option value="">Choose…</option>
          {nodes.map((n) => <option key={n.id} value={n.id}>{n.name} ({n.kind})</option>)}
        </select>
      </div>
      <div>
        <label className={LABEL_CLS}>To</label>
        <select className={INPUT_CLS} required value={form.toNodeId} onChange={(e) => set({ toNodeId: e.target.value })}>
          <option value="">Choose…</option>
          {nodes.map((n) => <option key={n.id} value={n.id}>{n.name} ({n.kind})</option>)}
        </select>
        {sameEnds && <p className="text-xs text-error mt-1">Pick a different far end.</p>}
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className={LABEL_CLS}>Kind</label>
          <select className={INPUT_CLS} value={form.kind} onChange={(e) => set({ kind: e.target.value })}>
            {ROUTE_KINDS.map((k) => <option key={k} value={k}>{pretty(k)}</option>)}
          </select>
        </div>
        <div>
          <label className={LABEL_CLS}>Status</label>
          <select className={INPUT_CLS} value={form.status} onChange={(e) => set({ status: e.target.value })}>
            {NODE_STATUSES.map((s) => <option key={s} value={s}>{pretty(s)}</option>)}
          </select>
        </div>
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className={LABEL_CLS}>Cores</label>
          <input className={INPUT_CLS} type="number" min="1" value={form.cores}
            onChange={(e) => set({ cores: e.target.value })} />
        </div>
        <div>
          <label className={LABEL_CLS}>Length (m)</label>
          <input className={INPUT_CLS} type="number" min="0" value={form.lengthMeters}
            onChange={(e) => set({ lengthMeters: e.target.value })} />
          <p className="text-xs text-on-surface-variant mt-1">Along the ground, not the straight line.</p>
        </div>
      </div>
      {error && <p className="text-sm text-error">{error}</p>}
      <div className="flex gap-2">
        <PrimaryButton type="submit" disabled={busy || sameEnds}>{busy ? 'Saving…' : 'Add route'}</PrimaryButton>
        <button type="button" onClick={onCancel}
          className="px-4 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high">
          Cancel
        </button>
      </div>
    </form>
  )
}

export default function FiberPage({ auth }) {
  const [plant, setPlant] = useState(null)
  const [subscribers, setSubscribers] = useState([])
  const [routers, setRouters] = useState([])
  const [panel, setPanel] = useState('legend') // legend | node | route | detail
  const [selected, setSelected] = useState(null)
  const [draft, setDraft] = useState(null)
  const [placing, setPlacing] = useState(false)
  const [hideKinds, setHideKinds] = useState([])
  const [msg, setMsg] = useState(null)
  const [tilesBlocked, setTilesBlocked] = useState(false)

  // A callback ref rather than useRef: the map container only mounts once
  // the plant has loaded, so a mount-time effect would fire too early and,
  // with empty deps, never run again.
  const [mapNode, setMapNode] = useState(null)
  const mapRef = useRef(null)
  const layerRef = useRef(null)
  const placingRef = useRef(false)

  const load = () =>
    api('/admin/fiber/plant', { auth }).then(setPlant).catch(() => setPlant({ nodes: [], routes: [], summary: null }))

  useEffect(() => {
    load()
    api('/admin/subscribers', { auth }).then(setSubscribers).catch(() => {})
    api('/admin/routers', { auth }).then(setRouters).catch(() => {})
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  // Keep the click handler's view of "am I placing?" current without
  // rebinding the map listener on every render.
  useEffect(() => { placingRef.current = placing }, [placing])

  // --- map setup, once the container is in the DOM ---
  useEffect(() => {
    if (!mapNode || mapRef.current) return
    const map = L.map(mapNode, { zoomControl: true }).setView(DEFAULT_CENTRE, DEFAULT_ZOOM)
    const tiles = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap contributors',
    })
    // Offline or behind a proxy, tiles fail — say so rather than showing grey.
    tiles.on('tileerror', () => setTilesBlocked(true))
    tiles.addTo(map)
    layerRef.current = L.layerGroup().addTo(map)
    map.on('click', (e) => {
      if (placingRef.current) {
        setDraft({ lat: e.latlng.lat, lng: e.latlng.lng })
        setPanel('node')
        setPlacing(false)
      }
    })
    mapRef.current = map
    // Leaflet mis-measures inside a freshly laid-out flex container.
    setTimeout(() => map.invalidateSize(), 200)
    return () => { map.remove(); mapRef.current = null }
  }, [mapNode])

  const visibleNodes = useMemo(
    () => (plant?.nodes || []).filter((n) => !hideKinds.includes(n.kind)),
    [plant, hideKinds]
  )

  // --- redraw whenever the plant or filters change ---
  useEffect(() => {
    const map = mapRef.current
    const layer = layerRef.current
    if (!map || !layer || !plant) return
    layer.clearLayers()

    const byId = new Map(plant.nodes.map((n) => [n.id, n]))

    plant.routes.forEach((r) => {
      const from = byId.get(r.fromNodeId)
      const to = byId.get(r.toNodeId)
      if (!from || !to) return
      if (hideKinds.includes(from.kind) && hideKinds.includes(to.kind)) return
      const points = [
        [from.latitude, from.longitude],
        ...(r.waypoints || []),
        [to.latitude, to.longitude],
      ]
      L.polyline(points, {
        color: STATUS_COLOUR[r.status] || '#64748b',
        weight: r.kind === 'BACKBONE' ? 5 : r.kind === 'DISTRIBUTION' ? 3 : 2,
        dashArray: r.status === 'PLANNED' ? '6 6' : undefined,
        opacity: 0.85,
      })
        .bindTooltip(`${r.name} — ${pretty(r.status)}${r.cores ? ` · ${r.cores} cores` : ''}`)
        .on('click', () => { setSelected({ type: 'route', data: r }); setPanel('detail') })
        .addTo(layer)
    })

    visibleNodes.forEach((n) => {
      L.marker([n.latitude, n.longitude], { icon: nodeIcon(n) })
        .bindTooltip(`${n.name} — ${pretty(n.kind)} · ${pretty(n.status)}`)
        .on('click', () => { setSelected({ type: 'node', data: n }); setPanel('detail') })
        .addTo(layer)
    })

    // Fit to the plant the first time there is something to fit to.
    if (plant.nodes.length > 0 && !map._fittedOnce) {
      const bounds = L.latLngBounds(plant.nodes.map((n) => [n.latitude, n.longitude]))
      map.fitBounds(bounds.pad(0.25), { maxZoom: 16 })
      map._fittedOnce = true
    }
  }, [plant, visibleNodes, hideKinds, mapNode])

  async function saveNode(body) {
    await api('/admin/fiber/nodes', { method: 'POST', auth, body })
    setDraft(null)
    setPanel('legend')
    setMsg({ ok: true, text: `${body.name} added to the plant.` })
    load()
  }

  async function saveRoute(body) {
    await api('/admin/fiber/routes', { method: 'POST', auth, body })
    setPanel('legend')
    setMsg({ ok: true, text: 'Route added.' })
    load()
  }

  async function setNodeStatus(node, status) {
    await api(`/admin/fiber/nodes/${node.id}/status`, { method: 'PATCH', auth, body: { status } })
      .catch((e) => setMsg({ ok: false, text: e.message }))
    setSelected(null)
    setPanel('legend')
    load()
  }

  async function setRouteStatus(route, status) {
    await api(`/admin/fiber/routes/${route.id}/status`, { method: 'PATCH', auth, body: { status } })
      .catch((e) => setMsg({ ok: false, text: e.message }))
    setSelected(null)
    setPanel('legend')
    load()
  }

  async function removeSelected() {
    if (!selected) return
    const { type, data } = selected
    if (!confirm(`Remove ${data.name}?`)) return
    try {
      await api(`/admin/fiber/${type === 'node' ? 'nodes' : 'routes'}/${data.id}`, { method: 'DELETE', auth })
      setSelected(null)
      setPanel('legend')
      load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    }
  }

  if (!plant) return <Skeleton className="h-96" />

  const s = plant.summary || {}

  return (
    <div>
      <PageHeader title="Fiber map" subtitle="Live plant across your coverage area — nodes, routes and drops.">
        <div className="flex gap-2">
          <button
            onClick={() => { setPlacing(!placing); setPanel('legend'); setSelected(null) }}
            aria-pressed={placing}
            className={`px-4 py-2 rounded-lg text-sm font-semibold cursor-pointer flex items-center gap-1.5 ${
              placing ? 'bg-primary text-on-primary' : 'border border-outline-variant hover:bg-surface-container-high'
            }`}
          >
            <Icon name="location_on" className="text-[18px]!" />
            {placing ? 'Click the map…' : 'Node'}
          </button>
          <button
            onClick={() => { setPanel('route'); setPlacing(false); setSelected(null) }}
            className="px-4 py-2 rounded-lg text-sm font-semibold border border-outline-variant cursor-pointer hover:bg-surface-container-high flex items-center gap-1.5"
          >
            <Icon name="timeline" className="text-[18px]!" /> Route
          </button>
        </div>
      </PageHeader>

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-4">
        <StatCard label="Nodes" value={s.nodes ?? 0} hint={`${s.routes ?? 0} cable runs`} accent="border-l-primary" />
        <StatCard
          label="Cable Laid"
          value={s.cableMetres ? `${(s.cableMetres / 1000).toFixed(2)} km` : '—'}
          hint="excludes decommissioned"
        />
        <StatCard
          label="Free Ports"
          value={s.freePorts ?? 0}
          hint={`${s.usedPorts ?? 0} of ${s.ports ?? 0} in use`}
        />
        <StatCard
          label="Faults"
          value={s.faults ?? 0}
          hint={s.faults > 0 ? 'nodes or routes down' : 'nothing reported'}
          accent={s.faults > 0 ? 'border-l-error' : ''}
        />
      </div>

      {msg && <p className={`mb-3 text-sm ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>}

      {tilesBlocked && (
        <p className="mb-3 text-sm text-[#b45309]">
          Map tiles could not load — this machine may be offline or behind a proxy. Nodes and routes are
          still drawn, just without the street background.
        </p>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-4 gap-4 items-start">
        <div className="lg:col-span-3 rounded-xl overflow-hidden border border-outline-variant/40 relative">
          <div ref={setMapNode} className="h-[560px] w-full bg-surface-container-low" />
          {placing && (
            <div className="absolute top-3 left-1/2 -translate-x-1/2 px-4 py-2 rounded-full bg-inverse-surface text-primary-fixed text-sm font-semibold shadow-lg pointer-events-none z-[500]">
              Click where the node sits
            </div>
          )}
        </div>

        <aside className="bg-surface-container-lowest rounded-lg border border-outline-variant p-5">
          {panel === 'legend' && (
            <>
              <h3 className="text-sm font-bold flex items-center gap-2 mb-3">
                <Icon name="map" className="text-[18px]!" /> Legend
              </h3>
              <p className="text-xs text-on-surface-variant mb-2">
                Shape marks the type. Tap a type to hide it.
              </p>
              <ul className="space-y-1 mb-5">
                {KINDS.map((k) => {
                  const hidden = hideKinds.includes(k.key)
                  const count = (plant.nodes || []).filter((n) => n.kind === k.key).length
                  return (
                    <li key={k.key}>
                      <button
                        onClick={() => setHideKinds(hidden
                          ? hideKinds.filter((x) => x !== k.key)
                          : [...hideKinds, k.key])}
                        className={`w-full flex items-center gap-2 px-2 py-1.5 rounded-lg text-sm cursor-pointer hover:bg-surface-container-high ${
                          hidden ? 'opacity-40' : ''
                        }`}
                        title={k.hint}
                      >
                        <LegendSwatch kind={k.key} />
                        <span className="flex-1 text-left">{k.label}</span>
                        <span className="text-xs text-on-surface-variant">{count}</span>
                      </button>
                    </li>
                  )
                })}
              </ul>

              <h4 className="text-xs font-bold tracking-wider uppercase text-on-surface-variant mb-2">Status</h4>
              <ul className="space-y-1.5">
                {NODE_STATUSES.map((st) => (
                  <li key={st} className="flex items-center gap-2 text-sm">
                    <span className="w-3 h-3 rounded-sm" style={{ background: STATUS_COLOUR[st] }} />
                    {pretty(st)}
                    <span className="ml-auto text-xs text-on-surface-variant">
                      {s.byStatus?.[st] ?? 0}
                    </span>
                  </li>
                ))}
              </ul>
              <p className="text-xs text-on-surface-variant mt-4">
                A dashed line is a planned run; solid is in the ground.
              </p>
            </>
          )}

          {panel === 'node' && (
            <>
              <h3 className="text-sm font-bold mb-3">Add a node</h3>
              <NodeForm
                nodes={plant.nodes}
                subscribers={subscribers}
                routers={routers}
                draft={draft}
                onCancel={() => { setDraft(null); setPanel('legend') }}
                onSave={saveNode}
              />
            </>
          )}

          {panel === 'route' && (
            <>
              <h3 className="text-sm font-bold mb-3">Add a route</h3>
              <RouteForm nodes={plant.nodes} onCancel={() => setPanel('legend')} onSave={saveRoute} />
            </>
          )}

          {panel === 'detail' && selected && (
            <>
              <div className="flex justify-between items-start mb-3">
                <div>
                  <h3 className="text-sm font-bold">{selected.data.name}</h3>
                  <p className="text-xs text-on-surface-variant">
                    {pretty(selected.data.kind)} · {pretty(selected.data.status)}
                  </p>
                </div>
                <button onClick={() => { setSelected(null); setPanel('legend') }} aria-label="Close"
                  className="text-on-surface-variant hover:text-on-surface cursor-pointer">
                  <Icon name="close" className="text-[18px]!" />
                </button>
              </div>

              <dl className="space-y-2 text-sm mb-4">
                {selected.type === 'node' ? (
                  <>
                    {selected.data.capacity != null && (
                      <div className="flex justify-between">
                        <dt className="text-on-surface-variant">Ports</dt>
                        <dd>{selected.data.used ?? 0} / {selected.data.capacity} used</dd>
                      </div>
                    )}
                    {selected.data.subscriberName && (
                      <div className="flex justify-between">
                        <dt className="text-on-surface-variant">Serves</dt>
                        <dd>{selected.data.subscriberName}</dd>
                      </div>
                    )}
                    <div className="flex justify-between">
                      <dt className="text-on-surface-variant">Position</dt>
                      <dd className="font-mono text-xs">
                        {selected.data.latitude.toFixed(5)}, {selected.data.longitude.toFixed(5)}
                      </dd>
                    </div>
                  </>
                ) : (
                  <>
                    {selected.data.cores && (
                      <div className="flex justify-between">
                        <dt className="text-on-surface-variant">Cores</dt>
                        <dd>{selected.data.cores}</dd>
                      </div>
                    )}
                    {selected.data.lengthMeters && (
                      <div className="flex justify-between">
                        <dt className="text-on-surface-variant">Length</dt>
                        <dd>{selected.data.lengthMeters} m</dd>
                      </div>
                    )}
                  </>
                )}
                {selected.data.notes && (
                  <div>
                    <dt className="text-on-surface-variant">Notes</dt>
                    <dd className="mt-0.5">{selected.data.notes}</dd>
                  </div>
                )}
              </dl>

              <p className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2">
                Set status
              </p>
              <div className="flex flex-wrap gap-1.5 mb-4">
                {NODE_STATUSES.map((st) => (
                  <button
                    key={st}
                    onClick={() => selected.type === 'node'
                      ? setNodeStatus(selected.data, st)
                      : setRouteStatus(selected.data, st)}
                    disabled={selected.data.status === st}
                    className="px-2.5 py-1.5 rounded-lg border border-outline-variant text-xs cursor-pointer hover:bg-surface-container-high disabled:opacity-40 disabled:cursor-default"
                  >
                    {pretty(st)}
                  </button>
                ))}
              </div>

              <button onClick={removeSelected}
                className="w-full px-3 py-2 rounded-lg border border-error/40 text-error text-sm font-semibold cursor-pointer hover:bg-error-container">
                Remove
              </button>
            </>
          )}
        </aside>
      </div>
    </div>
  )
}
