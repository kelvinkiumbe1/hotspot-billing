import { useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, PageHeader, PrimaryButton, INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

/**
 * Why one customer cannot get online.
 *
 * The support call is always the same sentence, and there are nine reasons it can
 * be true, living in nine different places. Somebody on the phone checks two of
 * them, guesses, and issues a replacement code that fails for the same reason.
 *
 * So the answer at the top is one sentence, and the nine checks are underneath it
 * for whoever wants to see the work. A tool that gives the wrong reason gets
 * distrusted after one wrong answer, which is why "the router is down and nothing
 * below could be checked" is a first-class outcome here rather than being
 * flattened into a list of failures.
 */

const VERDICT = {
  OK: ['check_circle', 'text-secondary'],
  WARN: ['error', 'text-warning'],
  PROBLEM: ['cancel', 'text-error'],
  UNKNOWN: ['help', 'text-on-surface-variant'],
}

export default function TroubleshootPage({ auth }) {
  const [code, setCode] = useState('')
  const [mac, setMac] = useState('')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)

  async function run(e) {
    e?.preventDefault()
    setBusy(true); setError(null); setResult(null)
    try {
      const q = new URLSearchParams({ code: code.trim() })
      if (mac.trim()) q.set('mac', mac.trim())
      setResult(await api(`/admin/troubleshoot/hotspot?${q}`, { auth }))
    } catch (err) {
      setError(err.message)
    } finally { setBusy(false) }
  }

  const worst = result?.problems > 0 ? 'PROBLEM'
    : result?.checks?.some((c) => c.verdict === 'WARN') ? 'WARN'
      : result?.checks?.some((c) => c.verdict === 'UNKNOWN') ? 'UNKNOWN' : 'OK'

  return (
    <>
      <PageHeader title="Why can't they connect?"
        subtitle="Check a code against the database and the router in one go." />

      <div className="space-y-4 max-w-3xl">
        <form onSubmit={run} className="rounded-lg border border-outline-variant p-4 space-y-3">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <label className={LABEL_CLS}>Their code</label>
              <input className={`${INPUT_CLS} font-mono`} value={code} autoFocus
                placeholder="ABC123" onChange={(e) => setCode(e.target.value.toUpperCase())} />
            </div>
            <div>
              <label className={LABEL_CLS}>Their device address (optional)</label>
              <input className={`${INPUT_CLS} font-mono`} value={mac} placeholder="AA:BB:CC:DD:EE:FF"
                onChange={(e) => setMac(e.target.value)} />
              {/* Worth asking for: two of the nine checks are impossible without
                  it, and one of those is the reason nobody can guess. */}
              <p className="text-xs text-on-surface-variant mt-1">
                Without it, &ldquo;already in use&rdquo; and &ldquo;tied to another
                pass&rdquo; cannot be checked &mdash; and the second is the reason
                nothing else explains.
              </p>
            </div>
          </div>
          <PrimaryButton type="submit" disabled={busy || !code.trim()}>
            {busy ? 'Checking…' : 'Check'}
          </PrimaryButton>
        </form>

        {error && <p className="text-sm text-error">{error}</p>}

        {result && (
          <>
            <div className={`rounded-lg border-2 p-4 ${
              worst === 'PROBLEM' ? 'border-error bg-error-container/20'
                : worst === 'WARN' ? 'border-warning bg-warning/10'
                  : worst === 'UNKNOWN' ? 'border-outline-variant'
                    : 'border-secondary bg-secondary-container/20'}`}>
              <div className="flex items-start gap-3">
                <Icon name={VERDICT[worst][0]} className={`${VERDICT[worst][1]} text-[24px]!`} />
                <div className="min-w-0">
                  <p className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant">
                    {worst === 'PROBLEM' ? 'Found it'
                      : worst === 'WARN' ? 'Probably this'
                        : worst === 'UNKNOWN' ? 'Could not check everything'
                          : 'Nothing wrong here'}
                  </p>
                  {/* The server's own sentence, verbatim -- it is the thing to
                      read down the phone. */}
                  <p className="text-base font-medium mt-0.5">{result.summary}</p>
                </div>
              </div>
              {result.voucher && (
                <p className="text-xs text-on-surface-variant mt-3 font-mono">
                  {result.voucher.code} · {result.voucher.plan || 'no plan'} · {result.voucher.status}
                  {result.voucher.phoneNumber ? ` · ${result.voucher.phoneNumber}` : ''}
                </p>
              )}
            </div>

            <div className="rounded-lg border border-outline-variant divide-y divide-outline-variant/40">
              {result.checks.map((c, i) => {
                const [icon, cls] = VERDICT[c.verdict] || VERDICT.UNKNOWN
                return (
                  <div key={i} className="p-3 flex items-start gap-3">
                    <Icon name={icon} className={`${cls} text-[18px]! mt-0.5`} />
                    <div className="min-w-0">
                      <p className="text-sm font-medium">{c.name}</p>
                      <p className="text-sm text-on-surface-variant">{c.detail}</p>
                      {c.fix && (
                        <p className="text-sm mt-1 flex items-start gap-1.5">
                          <Icon name="arrow_forward" className="text-[14px]! mt-1 text-primary" />
                          <span>{c.fix}</span>
                        </p>
                      )}
                    </div>
                  </div>
                )
              })}
            </div>
          </>
        )}
      </div>
    </>
  )
}
