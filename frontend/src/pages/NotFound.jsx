import { Link, useNavigate } from 'react-router-dom'
import { ArrowRight } from 'lucide-react'

/* A playful "ghost 404" — adapted from a shadcn/Next component to our
   Vite + React + Tailwind v4 stack: no next/image or next/link, no
   framer-motion (the float/entrance motion is plain CSS, matching the rest
   of the app), an inline SVG ghost instead of a remote image, and the
   black-+-yellow brand instead of the original white. */

function FlowButton({ text = 'Take me home' }) {
  return (
    <span className="group relative inline-flex items-center gap-1 overflow-hidden rounded-[100px] border-[1.5px] border-white/30 bg-transparent px-8 py-3 text-sm font-semibold text-white cursor-pointer transition-all duration-[600ms] ease-[cubic-bezier(0.23,1,0.32,1)] hover:border-transparent hover:text-on-primary hover:rounded-[12px] active:scale-[0.95]">
      <ArrowRight className="absolute w-4 h-4 left-[-25%] z-[9] group-hover:left-4 group-hover:stroke-[#161200] transition-all duration-[800ms] ease-[cubic-bezier(0.34,1.56,0.64,1)]" />
      <span className="relative z-[1] -translate-x-3 group-hover:translate-x-3 transition-all duration-[800ms] ease-out">{text}</span>
      <span className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-4 h-4 bg-primary rounded-full opacity-0 group-hover:w-[240px] group-hover:h-[240px] group-hover:opacity-100 transition-all duration-[800ms] ease-[cubic-bezier(0.19,1,0.22,1)] -z-0"></span>
      <ArrowRight className="absolute w-4 h-4 right-4 z-[9] group-hover:right-[-25%] group-hover:stroke-[#161200] transition-all duration-[800ms] ease-[cubic-bezier(0.34,1.56,0.64,1)]" />
    </span>
  )
}

function Ghost() {
  return (
    <svg viewBox="0 0 100 112" className="w-[80px] h-[90px] md:w-[120px] md:h-[132px] ghost-float drop-shadow-[0_10px_30px_rgba(253,191,45,0.25)]" aria-hidden="true">
      <path
        d="M50 4C26 4 18 26 18 50v52c0 5 6 7 9.3 3.2l6-6.8c2.2-2.5 6.2-2.5 8.4 0l5 5.7c2.2 2.5 6.2 2.5 8.4 0l5-5.7c2.2-2.5 6.2-2.5 8.4 0l6 6.8C77.9 109 84 107 84 102V50C84 26 74 4 50 4Z"
        fill="#f4f4f5"
      />
      <ellipse cx="38" cy="48" rx="6" ry="9" fill="#161616" />
      <ellipse cx="62" cy="48" rx="6" ry="9" fill="#161616" />
      <circle cx="30" cy="62" r="5" fill="#fdbf2d" opacity="0.5" />
      <circle cx="70" cy="62" r="5" fill="#fdbf2d" opacity="0.5" />
    </svg>
  )
}

export default function NotFound() {
  const navigate = useNavigate()
  return (
    <div className="relative min-h-screen flex flex-col items-center justify-center px-4 bg-[#0a0a0c] overflow-hidden">
      {/* ambient brand glow */}
      <div className="pointer-events-none absolute w-[520px] h-[520px] rounded-full bg-primary/15 blur-[120px] -z-0" />

      <div className="relative text-center fade-up">
        <div className="flex items-center justify-center gap-4 md:gap-6 mb-8 md:mb-12">
          <span className="text-[80px] md:text-[120px] font-bold text-white/70 leading-none select-none">4</span>
          <Ghost />
          <span className="text-[80px] md:text-[120px] font-bold text-white/70 leading-none select-none">4</span>
        </div>

        <h1 className="text-3xl md:text-5xl font-bold text-white/90 mb-4 md:mb-6 select-none">Boo! Page missing!</h1>
        <p className="text-lg md:text-xl text-white/50 mb-8 md:mb-12 select-none">
          Whoops! This page must be a ghost — it&apos;s not here.
        </p>

        <div className="flex justify-center">
          <Link to="/"><FlowButton text="Take me home" /></Link>
        </div>

        <div className="mt-10">
          <button
            onClick={() => navigate(-1)}
            className="text-white/50 hover:text-white/80 transition-opacity underline underline-offset-4 text-sm cursor-pointer select-none"
          >
            Go back to the previous page
          </button>
        </div>
      </div>
    </div>
  )
}
