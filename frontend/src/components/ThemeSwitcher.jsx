import { Sun, Moon } from 'lucide-react'
import { useState } from 'react'
import { motion } from 'framer-motion'

/**
 * The light/dark switch.
 *
 * Ported from a component written for Next.js, TypeScript and shadcn — none of
 * which this project uses. Three things had to change and nothing else did:
 *
 * - `next-themes` is gone. This app already resolves light/dark/system itself
 *   in Admin.jsx and stamps `data-theme` on the root, so the theme is passed in
 *   rather than fetched from a provider that does not exist here.
 * - No `'use client'`. That is a Next.js directive; Vite renders on the client
 *   already, and there is no server pass to guard against.
 * - No mounted/hydration dance for the same reason — there is no SSR output for
 *   the first client render to disagree with.
 *
 * The look, the spring and the particle burst are unchanged.
 */
export default function ThemeSwitcher({ isDark, onToggle }) {
  const [particles, setParticles] = useState([])

  function handleToggle() {
    // Three layers on slightly different clocks, which is what stops the burst
    // reading as one flat expanding disc.
    setParticles([0, 1, 2].map((i) => ({ id: i, delay: i * 0.1, duration: 0.6 + i * 0.1 })))
    setTimeout(() => setParticles([]), 1000)
    onToggle(isDark ? 'light' : 'dark')
  }

  return (
    <div className="relative inline-block">
      <motion.button
        type="button"
        onClick={handleToggle}
        className="relative flex h-[64px] w-[104px] items-center rounded-full p-[6px] transition-all duration-300 focus:outline-none cursor-pointer"
        style={{
          background: isDark
            ? 'radial-gradient(ellipse at top left, #1e293b 0%, #0f172a 40%, #020617 100%)'
            : 'radial-gradient(ellipse at top left, #ffffff 0%, #f1f5f9 40%, #cbd5e1 100%)',
          boxShadow: isDark
            ? `inset 5px 5px 12px rgba(0, 0, 0, 0.9),
               inset -5px -5px 12px rgba(71, 85, 105, 0.4),
               inset 8px 8px 16px rgba(0, 0, 0, 0.7),
               inset -8px -8px 16px rgba(100, 116, 139, 0.2),
               inset 0 2px 4px rgba(0, 0, 0, 1),
               inset 0 -2px 4px rgba(71, 85, 105, 0.4),
               inset 0 0 20px rgba(0, 0, 0, 0.6),
               0 1px 1px rgba(255, 255, 255, 0.05),
               0 2px 4px rgba(0, 0, 0, 0.4),
               0 8px 16px rgba(0, 0, 0, 0.4),
               0 16px 32px rgba(0, 0, 0, 0.3),
               0 24px 48px rgba(0, 0, 0, 0.2)`
            : `inset 5px 5px 12px rgba(148, 163, 184, 0.5),
               inset -5px -5px 12px rgba(255, 255, 255, 1),
               inset 8px 8px 16px rgba(100, 116, 139, 0.3),
               inset -8px -8px 16px rgba(255, 255, 255, 0.9),
               inset 0 2px 4px rgba(148, 163, 184, 0.4),
               inset 0 -2px 4px rgba(255, 255, 255, 1),
               inset 0 0 20px rgba(203, 213, 225, 0.3),
               0 1px 2px rgba(255, 255, 255, 1),
               0 2px 4px rgba(0, 0, 0, 0.1),
               0 8px 16px rgba(0, 0, 0, 0.08),
               0 16px 32px rgba(0, 0, 0, 0.06),
               0 24px 48px rgba(0, 0, 0, 0.04)`,
          border: isDark
            ? '2px solid rgba(51, 65, 85, 0.6)'
            : '2px solid rgba(203, 213, 225, 0.6)',
        }}
        aria-label={`Switch to ${isDark ? 'light' : 'dark'} mode`}
        role="switch"
        aria-checked={isDark}
        whileTap={{ scale: 0.98 }}
      >
        {/* Deep inner groove */}
        <div className="absolute inset-[3px] rounded-full pointer-events-none"
          style={{
            boxShadow: isDark
              ? 'inset 0 2px 6px rgba(0, 0, 0, 0.9), inset 0 -1px 3px rgba(71, 85, 105, 0.3)'
              : 'inset 0 2px 6px rgba(100, 116, 139, 0.4), inset 0 -1px 3px rgba(255, 255, 255, 0.8)',
          }} />

        {/* Glossy overlay */}
        <div className="absolute inset-0 rounded-full pointer-events-none"
          style={{
            background: isDark
              ? `radial-gradient(ellipse at top, rgba(71, 85, 105, 0.15) 0%, transparent 50%),
                 linear-gradient(to bottom, rgba(71, 85, 105, 0.2) 0%, transparent 30%, transparent 70%, rgba(0, 0, 0, 0.3) 100%)`
              : `radial-gradient(ellipse at top, rgba(255, 255, 255, 0.8) 0%, transparent 50%),
                 linear-gradient(to bottom, rgba(255, 255, 255, 0.7) 0%, transparent 30%, transparent 70%, rgba(148, 163, 184, 0.15) 100%)`,
            mixBlendMode: 'overlay',
          }} />

        {/* Ambient occlusion */}
        <div className="absolute inset-0 rounded-full pointer-events-none"
          style={{
            boxShadow: isDark
              ? 'inset 0 0 15px rgba(0, 0, 0, 0.5)'
              : 'inset 0 0 15px rgba(148, 163, 184, 0.2)',
          }} />

        {/* Background icons */}
        <div className="absolute inset-0 flex items-center justify-between px-4">
          <Sun size={20} className={isDark ? 'text-yellow-100' : 'text-amber-600'} />
          <Moon size={20} className={isDark ? 'text-yellow-100' : 'text-slate-700'} />
        </div>

        {/* The thumb */}
        <motion.div
          className="relative z-10 flex h-[44px] w-[44px] items-center justify-center rounded-full overflow-hidden"
          style={{
            background: isDark
              ? 'linear-gradient(145deg, #64748b 0%, #475569 50%, #334155 100%)'
              : 'linear-gradient(145deg, #ffffff 0%, #fefefe 50%, #f8fafc 100%)',
            boxShadow: isDark
              ? `inset 2px 2px 4px rgba(100, 116, 139, 0.4),
                 inset -2px -2px 4px rgba(0, 0, 0, 0.8),
                 inset 0 1px 1px rgba(255, 255, 255, 0.15),
                 0 1px 2px rgba(255, 255, 255, 0.1),
                 0 8px 32px rgba(0, 0, 0, 0.6),
                 0 4px 12px rgba(0, 0, 0, 0.5),
                 0 2px 4px rgba(0, 0, 0, 0.4)`
              : `inset 2px 2px 4px rgba(203, 213, 225, 0.3),
                 inset -2px -2px 4px rgba(255, 255, 255, 1),
                 inset 0 1px 2px rgba(255, 255, 255, 1),
                 0 1px 2px rgba(255, 255, 255, 1),
                 0 8px 32px rgba(0, 0, 0, 0.18),
                 0 4px 12px rgba(0, 0, 0, 0.12),
                 0 2px 4px rgba(0, 0, 0, 0.08)`,
            border: isDark
              ? '2px solid rgba(148, 163, 184, 0.3)'
              : '2px solid rgba(255, 255, 255, 0.9)',
          }}
          animate={{ x: isDark ? 46 : 0 }}
          transition={{ type: 'spring', stiffness: 300, damping: 20 }}
        >
          <div className="absolute inset-0 rounded-full pointer-events-none"
            style={{
              background: 'linear-gradient(to bottom, rgba(255, 255, 255, 0.4) 0%, transparent 40%, rgba(0, 0, 0, 0.1) 100%)',
              mixBlendMode: 'overlay',
            }} />

          {particles.map((particle) => (
            <motion.div key={particle.id}
              className="absolute inset-0 flex items-center justify-center pointer-events-none">
              <motion.div
                className="absolute rounded-full"
                style={{
                  width: '10px',
                  height: '10px',
                  background: isDark
                    ? 'radial-gradient(circle, rgba(147, 197, 253, 0.5) 0%, rgba(147, 197, 253, 0) 70%)'
                    : 'radial-gradient(circle, rgba(251, 191, 36, 0.7) 0%, rgba(251, 191, 36, 0) 70%)',
                }}
                initial={{ scale: 0, opacity: 0 }}
                animate={{ scale: isDark ? 6 : 8, opacity: [0, 1, 0] }}
                transition={{
                  duration: isDark ? 0.5 : particle.duration,
                  delay: particle.delay,
                  ease: 'easeOut',
                }}
              >
                <div className="absolute inset-0 rounded-full opacity-40"
                  style={{
                    backgroundImage: `url("data:image/svg+xml,%3Csvg viewBox='0 0 200 200' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='noiseFilter'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23noiseFilter)'/%3E%3C/svg%3E")`,
                    mixBlendMode: 'overlay',
                  }} />
              </motion.div>
            </motion.div>
          ))}

          <div className="relative z-10">
            {isDark ? <Moon size={20} className="text-yellow-200" />
              : <Sun size={20} className="text-amber-500" />}
          </div>
        </motion.div>
      </motion.button>
    </div>
  )
}
