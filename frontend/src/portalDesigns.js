/*
 * Captive-portal designs. Unlike the old "themes" (one layout, ten
 * palettes), each design is a complete, distinct UI: its own layout,
 * typography, colours and component styling. Portal.jsx switches its
 * whole plans-screen structure on the design key; the shared screens
 * (pay / waiting / success / error) inherit the design's tokens.
 *
 * One registry so the live portal and the admin gallery previews can
 * never drift apart.
 */

function makeVars(p) {
  return {
    '--color-background': p.bg,
    '--color-on-background': p.text,
    '--color-surface': p.surf,
    '--color-on-surface': p.text,
    '--color-on-surface-variant': p.muted,
    '--color-surface-bright': p.surf3,
    '--color-surface-dim': p.bg,
    '--color-surface-variant': p.outline,
    '--color-surface-container-lowest': p.surf,
    '--color-surface-container-low': p.surf2,
    '--color-surface-container': p.surf2,
    '--color-surface-container-high': p.surf3,
    '--color-surface-container-highest': p.surf3,
    '--color-primary': p.accent,
    '--color-on-primary': p.onAccent,
    '--color-primary-container': p.accent,
    '--color-on-primary-container': p.onAccent,
    '--color-primary-fixed': p.accent,
    '--color-primary-fixed-dim': p.tint,
    '--color-surface-tint': p.tint,
    '--color-secondary': p.secondary || p.accent,
    '--color-on-secondary': p.onSecondary || p.onAccent,
    '--color-secondary-container': p.outline,
    '--color-on-secondary-container': p.accent,
    '--color-outline': p.muted,
    '--color-outline-variant': p.outline,
    '--portal-glow1': `rgba(${p.glow}, 0.10)`,
    '--portal-glow2': `rgba(${p.glow}, 0.07)`,
    '--portal-heading-font': p.headingFont || 'inherit',
    '--portal-body-font': p.bodyFont || 'inherit',
  }
}

const SERIF = 'Georgia, "Times New Roman", serif'
const MONO = '"Cascadia Code", "JetBrains Mono", Consolas, monospace'

// key, name, one-line description for the admin gallery, and the palette.
const RAW = [
  {
    key: 'CLASSIC', name: 'Signature',
    desc: 'The original look — black canvas, amber accent, big photo hero, plans stacked by duration.',
    p: { bg: '#000000', surf: '#0a0a0a', surf2: '#191919', surf3: '#272727', accent: '#fdbf2d', onAccent: '#000000', text: '#ffffff', muted: '#b9b9b9', outline: '#3a3a3a', tint: '#e0aa22', glow: '253,191,45' },
    confetti: ['#fdbf2d', '#e0aa22', '#ffd479', '#ffffff'],
  },
  {
    key: 'BREEZE', name: 'Breeze',
    desc: 'Light and airy — white cards, mint-green accent, plan categories as filter tabs, voucher box up top.',
    p: { bg: '#f4f9f6', surf: '#ffffff', surf2: '#eaf3ee', surf3: '#dfece5', accent: '#0e9f6e', onAccent: '#ffffff', text: '#13291f', muted: '#587268', outline: '#d3e4db', tint: '#0b8a5f', glow: '14,159,110' },
    confetti: ['#0e9f6e', '#34d399', '#a7f3d0', '#ffffff'],
  },
  {
    key: 'POSTER', name: 'Market Poster',
    desc: 'Bold street-poster style — cream paper, big serif headlines, plans as chunky price tags.',
    p: { bg: '#f7efdf', surf: '#fffaf0', surf2: '#f1e6cd', surf3: '#e9dbba', accent: '#e8590c', onAccent: '#ffffff', text: '#26180a', muted: '#82694a', outline: '#2612081f', tint: '#c94b08', glow: '232,89,12', headingFont: SERIF, secondary: '#8f6b00', onSecondary: '#fffaf0' },
    confetti: ['#e8590c', '#f59f00', '#26180a', '#fffaf0'],
  },
  {
    key: 'MATRIX', name: 'Compact Grid',
    desc: 'Dense and fast — every plan on screen at once as small tappable tiles. Best when you sell many plans.',
    p: { bg: '#0b1020', surf: '#111830', surf2: '#161f3e', surf3: '#1d294e', accent: '#5b8cff', onAccent: '#061027', text: '#e8eeff', muted: '#93a3c7', outline: '#27345c', tint: '#3f6fe8', glow: '91,140,255' },
    confetti: ['#5b8cff', '#3f6fe8', '#a5c0ff', '#ffffff'],
  },
  {
    key: 'STEPS', name: 'Step-by-Step',
    desc: 'Guided and reassuring — a numbered how-to-connect card first, then simple plan rows. Great for first-timers.',
    p: { bg: '#f3f6fa', surf: '#ffffff', surf2: '#e9eef6', surf3: '#dde6f2', accent: '#2563eb', onAccent: '#ffffff', text: '#172234', muted: '#5a6b83', outline: '#d4deec', tint: '#1d4ed8', glow: '37,99,235' },
    confetti: ['#2563eb', '#60a5fa', '#bfdbfe', '#ffffff'],
  },
  {
    key: 'NEON', name: 'Terminal',
    desc: 'Cyber terminal — pure black, monospace type, glowing green accents, plans as a command-line menu.',
    p: { bg: '#000000', surf: '#04100a', surf2: '#07180f', surf3: '#0c2417', accent: '#00ff9c', onAccent: '#002915', text: '#d8ffe9', muted: '#7dbf9b', outline: '#12402a', tint: '#00d182', glow: '0,255,156', headingFont: MONO, bodyFont: MONO },
    confetti: ['#00ff9c', '#00d182', '#7dffce', '#ffffff'],
  },
]

export const PORTAL_DESIGNS = RAW.map((d) => ({
  key: d.key,
  name: d.name,
  desc: d.desc,
  confetti: d.confetti,
  // Raw swatch colours for the admin gallery phone mockups.
  preview: {
    bg: d.p.bg, surface: d.p.surf, surface2: d.p.surf2, accent: d.p.accent,
    onAccent: d.p.onAccent, text: d.p.text, muted: d.p.muted, outline: d.p.outline,
    headingFont: d.p.headingFont || 'inherit', bodyFont: d.p.bodyFont || 'inherit',
  },
  vars: makeVars(d.p),
}))

export const DESIGN_KEYS = PORTAL_DESIGNS.map((d) => d.key)

// Old values (layout templates and colour themes) map onto the nearest design
// so an operator who saved settings before this change lands somewhere sane.
const LEGACY = { GRID: 'MATRIX', MINIMAL: 'BREEZE', AMBER: 'CLASSIC' }

export function normalizeDesignKey(key) {
  if (!key) return null
  const k = String(key).trim().toUpperCase()
  if (DESIGN_KEYS.includes(k)) return k
  return LEGACY[k] || null
}

export function designByKey(key) {
  return PORTAL_DESIGNS.find((d) => d.key === normalizeDesignKey(key)) || PORTAL_DESIGNS[0]
}
