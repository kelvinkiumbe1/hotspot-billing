import { api } from './api.js'

// WebAuthn speaks ArrayBuffers; our API speaks base64url. These two convert
// between the wire form and what navigator.credentials expects.
function b64urlToBuf(value) {
  const s = value.replace(/-/g, '+').replace(/_/g, '/')
  const padded = s + '='.repeat((4 - (s.length % 4)) % 4)
  const bin = atob(padded)
  const bytes = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
  return bytes.buffer
}

function bufToB64url(buf) {
  const bytes = new Uint8Array(buf)
  let bin = ''
  for (const b of bytes) bin += String.fromCharCode(b)
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** Passkeys need a secure context (HTTPS or localhost) and the API present. */
export function passkeySupported() {
  return typeof window !== 'undefined'
    && window.PublicKeyCredential
    && navigator.credentials
    && window.isSecureContext
}

/**
 * Enrol a passkey for the signed-in staff member. `auth` is their current
 * session header ("Bearer …"); `label` names the device in their list.
 */
export async function enrollPasskey(auth, label) {
  const opts = await api('/auth/passkey/register/start', { method: 'POST', auth })
  const publicKey = {
    challenge: b64urlToBuf(opts.challenge),
    rp: opts.rp,
    user: {
      id: b64urlToBuf(opts.user.id),
      name: opts.user.name,
      displayName: opts.user.displayName,
    },
    pubKeyCredParams: opts.pubKeyCredParams,
    timeout: opts.timeout,
    attestation: opts.attestation,
    authenticatorSelection: opts.authenticatorSelection,
    excludeCredentials: (opts.excludeCredentials || []).map((c) => ({
      type: c.type,
      id: b64urlToBuf(c.id),
    })),
  }
  const cred = await navigator.credentials.create({ publicKey })
  return api('/auth/passkey/register/finish', {
    method: 'POST',
    auth,
    body: {
      id: cred.id,
      clientDataJSON: bufToB64url(cred.response.clientDataJSON),
      attestationObject: bufToB64url(cred.response.attestationObject),
      label,
    },
  })
}

/**
 * Sign in with a passkey for `username`. Returns the same session payload as
 * a password login ({ token, role, … }). Throws if the account has no
 * passkey, or if the person cancels the browser prompt.
 */
export async function passkeyLogin(username) {
  const opts = await api('/auth/passkey/login/start', { method: 'POST', body: { username } })
  const publicKey = {
    challenge: b64urlToBuf(opts.challenge),
    rpId: opts.rpId,
    timeout: opts.timeout,
    userVerification: opts.userVerification,
    allowCredentials: (opts.allowCredentials || []).map((c) => ({
      type: c.type,
      id: b64urlToBuf(c.id),
    })),
  }
  const assertion = await navigator.credentials.get({ publicKey })
  return api('/auth/passkey/login/finish', {
    method: 'POST',
    body: {
      username,
      response: {
        id: assertion.id,
        clientDataJSON: bufToB64url(assertion.response.clientDataJSON),
        authenticatorData: bufToB64url(assertion.response.authenticatorData),
        signature: bufToB64url(assertion.response.signature),
        userHandle: assertion.response.userHandle ? bufToB64url(assertion.response.userHandle) : null,
      },
    },
  })
}
