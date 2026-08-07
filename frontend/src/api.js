// `auth` is a full Authorization header value — "Bearer <token>" for a
// signed-in session, or "Basic <base64>" for the break-glass config account
// and field technicians. Sending it verbatim lets both schemes coexist while
// the caller no longer has to know which one it holds.
export async function api(path, { method = 'GET', body, auth } = {}) {
  const headers = {}
  const isForm = body instanceof FormData
  if (body && !isForm) headers['Content-Type'] = 'application/json'
  if (auth) headers['Authorization'] = auth
  const res = await fetch('/api' + path, {
    method,
    headers,
    body: body ? (isForm ? body : JSON.stringify(body)) : undefined,
  })
  if (!res.ok) {
    let message = `Request failed (${res.status})`
    try {
      const data = await res.json()
      message = data.message || message
    } catch { /* non-JSON error body */ }
    const err = new Error(message)
    err.status = res.status
    throw err
  }
  const text = await res.text()
  return text ? JSON.parse(text) : null
}
