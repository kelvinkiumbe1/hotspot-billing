/*
 * Captive-portal themes. One shared registry so the live portal and the
 * admin gallery preview can never drift apart. Each theme is a compact
 * palette; makeVars() expands it into the CSS custom properties that the
 * .portal-theme Tailwind tokens read, applied inline on the portal so it
 * overrides the stylesheet defaults without a rebuild.
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
    '--portal-heading-font': p.serif ? 'Georgia, "Times New Roman", serif' : 'inherit',
  }
}

// key, name, one-line description, the palette, and preview/structure flags.
const RAW = [
  { key: 'AMBER', name: 'Amber Gold', desc: 'Black with a warm amber-gold accent. The signature look.',
    p: { bg: '#000000', surf: '#0a0a0a', surf2: '#191919', surf3: '#272727', accent: '#fdbf2d', onAccent: '#000000', text: '#ffffff', muted: '#b9b9b9', outline: '#3a3a3a', tint: '#e0aa22', glow: '253,191,45' } },
  { key: 'EMERALD', name: 'Emerald OLED', desc: 'Pure black, sharp corners, neon-green institutional feel.',
    p: { bg: '#000000', surf: '#0c0f0c', surf2: '#141a14', surf3: '#1f291f', accent: '#00e676', onAccent: '#00220f', text: '#eaffea', muted: '#9fd6a8', outline: '#244024', tint: '#00c853', glow: '0,230,118' }, sharp: true },
  { key: 'COBALT', name: 'Cobalt Glass', desc: 'Midnight navy with a cool cobalt-blue accent.',
    p: { bg: '#0a0c10', surf: '#12161d', surf2: '#171d27', surf3: '#202a38', accent: '#2f9bff', onAccent: '#001022', text: '#eaf2ff', muted: '#9fb4cc', outline: '#24384f', tint: '#1f7fe0', glow: '47,155,255' } },
  { key: 'CRIMSON', name: 'Crimson Executive', desc: 'Deep crimson and gold with elegant serif headings.',
    p: { bg: '#141214', surf: '#1b1416', surf2: '#241a1d', surf3: '#2e2024', accent: '#e11d2a', onAccent: '#ffffff', text: '#f3e9ea', muted: '#cbb0b3', outline: '#3a2226', tint: '#b8151f', glow: '225,29,42', secondary: '#d4af37', onSecondary: '#1a1416' }, serif: true },
  { key: 'VIOLET', name: 'Violet Glass', desc: 'Dark purple canvas with an electric-violet accent.',
    p: { bg: '#0e0a18', surf: '#16111f', surf2: '#1e1729', surf3: '#281f36', accent: '#b94dff', onAccent: '#14061f', text: '#efe6ff', muted: '#bcabd6', outline: '#382b4f', tint: '#a23df0', glow: '185,77,255' } },
  { key: 'NEON', name: 'Neon Black', desc: 'Absolute black with a bright cyan neon accent.',
    p: { bg: '#000000', surf: '#0a0a0a', surf2: '#141414', surf3: '#1f1f1f', accent: '#00e5ff', onAccent: '#001a1f', text: '#ffffff', muted: '#b9c6c9', outline: '#143b40', tint: '#00b8d4', glow: '0,229,255' } },
  { key: 'STEEL', name: 'Industrial Steel', desc: 'Gunmetal grey, square edges, lime industrial accent.',
    p: { bg: '#17181a', surf: '#1e2022', surf2: '#26292c', surf3: '#303437', accent: '#7cff6b', onAccent: '#06210a', text: '#e6e8ea', muted: '#aab0b4', outline: '#3a3f43', tint: '#58e04a', glow: '124,255,107' }, sharp: true },
  { key: 'SLATE', name: 'Slate Precision', desc: 'Cool slate with a calm emerald-teal accent.',
    p: { bg: '#101314', surf: '#171b1c', surf2: '#1e2426', surf3: '#283032', accent: '#34d399', onAccent: '#00241a', text: '#e2e8e6', muted: '#9fb4ad', outline: '#2b3a37', tint: '#10b981', glow: '52,211,153' } },
  { key: 'OCEAN', name: 'Deep Ocean', desc: 'Deep sea-blue with a bright aqua accent.',
    p: { bg: '#06131a', surf: '#0c1c26', surf2: '#10262f', surf3: '#163540', accent: '#22d3ee', onAccent: '#002027', text: '#e3f4f8', muted: '#9fc0c9', outline: '#1f414c', tint: '#06b6d4', glow: '34,211,238' } },
  { key: 'ROSE', name: 'Rose Quartz', desc: 'Warm near-black with a soft rose-pink accent.',
    p: { bg: '#140a0f', surf: '#1d1016', surf2: '#26161d', surf3: '#311d26', accent: '#ff5d8f', onAccent: '#2a0714', text: '#ffe9f0', muted: '#d6adb9', outline: '#4a2233', tint: '#f0417a', glow: '255,93,143' } },
]

export const PORTAL_THEMES = RAW.map((t) => ({
  key: t.key,
  name: t.name,
  desc: t.desc,
  sharp: !!t.sharp,
  serif: !!t.serif,
  // Raw swatch colours for the admin gallery preview.
  preview: { bg: t.p.bg, surface: t.p.surf2, accent: t.p.accent, onAccent: t.p.onAccent, text: t.p.text, muted: t.p.muted },
  vars: makeVars(t.p),
}))

export const THEME_KEYS = PORTAL_THEMES.map((t) => t.key)

export function themeByKey(key) {
  return PORTAL_THEMES.find((t) => t.key === key) || PORTAL_THEMES[0]
}
