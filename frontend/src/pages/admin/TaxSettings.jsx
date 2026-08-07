import { useEffect, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, INPUT_CLS, LABEL_CLS, fmtKES,
} from '../../components/ui.jsx'

export default function TaxSettingsPage({ auth }) {
  const [form, setForm] = useState(null)
  const [saved, setSaved] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  useEffect(() => {
    api('/admin/tax/settings', { auth })
      .then((d) => {
        setForm({
          vatEnabled: d.vatEnabled,
          vatRate: d.vatRate ?? 16,
          pricesIncludeVat: d.pricesIncludeVat,
          kraPin: d.kraPin || '',
          legalName: d.legalName || '',
          addressLine: d.addressLine || '',
          invoicePrefix: d.invoicePrefix || 'INV',
        })
        setSaved(d)
      })
      .catch((e) => setMsg({ ok: false, text: e.message }))
  }, [auth])

  const set = (patch) => setForm((f) => ({ ...f, ...patch }))

  // Worked locally so the effect of the inclusive switch is visible before
  // saving, rather than only after.
  const example = (() => {
    if (!form) return null
    const charge = 3500
    const rate = Number(form.vatRate) || 0
    if (!form.vatEnabled || rate === 0) return { net: charge, vat: 0, gross: charge }
    if (form.pricesIncludeVat) {
      const net = Math.round((charge / (1 + rate / 100)) * 100) / 100
      return { net, vat: Math.round((charge - net) * 100) / 100, gross: charge }
    }
    const vat = Math.round(charge * (rate / 100) * 100) / 100
    return { net: charge, vat, gross: Math.round((charge + vat) * 100) / 100 }
  })()

  async function save(e) {
    e.preventDefault()
    setBusy(true)
    setMsg(null)
    try {
      const d = await api('/admin/tax/settings', {
        method: 'PUT',
        auth,
        body: { ...form, vatRate: Number(form.vatRate) },
      })
      setSaved(d)
      setMsg({ ok: true, text: 'VAT settings saved. New invoices will use them.' })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
    }
  }

  if (!form) return <Skeleton className="h-64" />

  return (
    <div>
      <PageHeader title="VAT" subtitle="How tax is worked out and what appears on a tax invoice." />

      <form onSubmit={save} className="max-w-3xl space-y-6">
        <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
          <label className="flex items-start gap-3 cursor-pointer">
            <input type="checkbox" className="mt-1" checked={form.vatEnabled}
              onChange={(e) => set({ vatEnabled: e.target.checked })} />
            <span>
              <span className="text-base font-semibold block">Charge VAT</span>
              <span className="text-sm text-on-surface-variant">
                Leave this off if you are below the registration threshold. Invoices will then be plain
                invoices rather than tax invoices.
              </span>
            </span>
          </label>

          {form.vatEnabled && (
            <div className="mt-5 space-y-5">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label className={LABEL_CLS}>Rate (%)</label>
                  <input className={INPUT_CLS} type="number" min="0" max="100" step="0.01"
                    value={form.vatRate} onChange={(e) => set({ vatRate: e.target.value })} />
                  <p className="text-xs text-on-surface-variant mt-1">Kenya is 16% at present.</p>
                </div>
                <div>
                  <label className={LABEL_CLS}>KRA PIN *</label>
                  <input className={INPUT_CLS} required value={form.kraPin}
                    onChange={(e) => set({ kraPin: e.target.value.toUpperCase() })}
                    placeholder="P051234567X" />
                  <p className="text-xs text-on-surface-variant mt-1">Required on every tax invoice.</p>
                </div>
              </div>

              <div>
                <p className={LABEL_CLS}>Are your prices VAT-inclusive?</p>
                <div className="space-y-2">
                  <label className={`flex items-start gap-3 p-3 rounded-lg border cursor-pointer ${
                    form.pricesIncludeVat ? 'border-primary bg-primary-container/15' : 'border-outline-variant'
                  }`}>
                    <input type="radio" className="mt-1" checked={form.pricesIncludeVat}
                      onChange={() => set({ pricesIncludeVat: true })} />
                    <span>
                      <span className="text-sm font-semibold block">Yes — the price already includes VAT</span>
                      <span className="text-xs text-on-surface-variant">
                        The customer pays exactly the figure on their package, and the tax is worked back out
                        of it. This is the usual Kenyan arrangement.
                      </span>
                    </span>
                  </label>
                  <label className={`flex items-start gap-3 p-3 rounded-lg border cursor-pointer ${
                    !form.pricesIncludeVat ? 'border-primary bg-primary-container/15' : 'border-outline-variant'
                  }`}>
                    <input type="radio" className="mt-1" checked={!form.pricesIncludeVat}
                      onChange={() => set({ pricesIncludeVat: false })} />
                    <span>
                      <span className="text-sm font-semibold block">No — add VAT on top</span>
                      <span className="text-xs text-on-surface-variant">
                        The package price is before tax, and the customer is billed more than it.
                      </span>
                    </span>
                  </label>
                </div>
              </div>

              {example && (
                <div className="p-4 rounded-lg bg-surface-container-low border border-outline-variant">
                  <p className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2">
                    A KES 3,500 package would invoice as
                  </p>
                  <dl className="text-sm space-y-1">
                    <div className="flex justify-between"><dt>Subtotal (net)</dt><dd className="tabular-nums">{fmtKES(example.net)}</dd></div>
                    <div className="flex justify-between"><dt>VAT @ {form.vatRate}%</dt><dd className="tabular-nums">{fmtKES(example.vat)}</dd></div>
                    <div className="flex justify-between font-bold border-t border-outline-variant pt-1 mt-1">
                      <dt>Total the customer pays</dt><dd className="tabular-nums">{fmtKES(example.gross)}</dd>
                    </div>
                  </dl>
                  {!form.pricesIncludeVat && (
                    <p className="mt-2 text-xs text-[#b45309]">
                      Note this bills them {fmtKES(example.gross - 3500)} more than the package price.
                    </p>
                  )}
                </div>
              )}
            </div>
          )}
        </section>

        <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
          <h3 className="text-base font-bold mb-1">What appears on the invoice</h3>
          <p className="text-xs text-on-surface-variant mb-4">Your details as they should be printed.</p>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className={LABEL_CLS}>Registered name</label>
              <input className={INPUT_CLS} value={form.legalName}
                onChange={(e) => set({ legalName: e.target.value })} placeholder="e.g. SPA Limited" />
              <p className="text-xs text-on-surface-variant mt-1">Falls back to your business name.</p>
            </div>
            <div>
              <label className={LABEL_CLS}>Address</label>
              <input className={INPUT_CLS} value={form.addressLine}
                onChange={(e) => set({ addressLine: e.target.value })} placeholder="Nairobi, Kenya" />
            </div>
            <div>
              <label className={LABEL_CLS}>Invoice number prefix</label>
              <input className={INPUT_CLS} maxLength={8} value={form.invoicePrefix}
                onChange={(e) => set({ invoicePrefix: e.target.value.toUpperCase() })} />
              <p className="text-xs text-on-surface-variant mt-1">
                Next will look like {form.invoicePrefix || 'INV'}-{new Date().getFullYear()}-000123
              </p>
            </div>
          </div>
        </section>

        <div className="p-4 rounded-lg bg-surface-container-low border border-outline-variant">
          <p className="text-sm">
            <strong>On eTIMS:</strong> these invoices carry the right arithmetic and the fields KRA expects,
            but they are not transmitted to eTIMS. That needs a separate integration and KRA onboarding —
            worth confirming your obligation with whoever files your returns.
          </p>
        </div>

        {msg && <p className={`text-sm ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>}

        <div className="flex items-center gap-3">
          <PrimaryButton type="submit" disabled={busy}>{busy ? 'Saving…' : 'Save VAT settings'}</PrimaryButton>
          {saved?.updatedAt && (
            <span className="text-xs text-on-surface-variant flex items-center gap-1">
              <Icon name="history" className="text-[14px]!" /> last changed {new Date(saved.updatedAt).toLocaleString('en-KE')}
            </span>
          )}
        </div>

        <p className="text-xs text-on-surface-variant">
          Changing these affects invoices issued from now on. Existing invoices keep the rate and treatment
          they were issued under, so a document already sent to a customer never changes.
        </p>
      </form>
    </div>
  )
}
