"""Contrast audit over index.css.

    python frontend/tools/audit-contrast.py

Run it after touching any --color-* token. Every theme in this app is a set of
token overrides, so a value that reads fine in one palette can be unreadable in
another, and nothing in the build catches it -- the CSS is valid either way.
The amber primary in the admin light theme sat at 1.66:1 for months behind a
comment claiming it had been darkened.

Parses each theme block, resolves its token values against the @theme defaults
(themes only override some tokens), and checks every Material-3 pair that a
human actually reads. Exits non-zero if anything fails, so it can be re-run
after each fix until it is clean.
"""
import io
import os
import re
import sys

CSS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "index.css")

# (foreground, background, minimum ratio, what it is)
PAIRS = [
    ("on-background", "background", 4.5, "body text"),
    ("on-surface", "surface", 4.5, "text on a card"),
    ("on-surface-variant", "surface", 4.5, "muted text"),
    ("on-surface-variant", "surface-container", 4.5, "muted text on a container"),
    ("on-surface", "surface-container-high", 4.5, "text on a raised container"),
    ("on-primary", "primary", 4.5, "text on a primary button"),
    ("on-primary-container", "primary-container", 4.5, "text on a primary panel"),
    ("on-secondary", "secondary", 4.5, "text on a secondary button"),
    ("on-secondary-container", "secondary-container", 4.5, "text on a secondary panel"),
    ("on-error", "error", 4.5, "text on an error button"),
    ("on-error-container", "error-container", 4.5, "text on an error panel"),
    ("on-warning", "warning", 4.5, "text on a warning button"),
    ("on-warning-container", "warning-container", 4.5, "text on a warning panel"),
    # Roles used directly as text colour, which is how the app uses them.
    ("primary", "surface", 4.5, "primary as text"),
    ("secondary", "surface", 4.5, "secondary as text"),
    ("error", "surface", 4.5, "error as text"),
    ("warning", "surface", 4.5, "warning as text"),
    ("primary", "background", 4.5, "primary as text on the page"),
    # Non-text: borders and dividers only need 3:1.
    ("outline", "surface", 3.0, "border"),
    # outline-variant is M3's decorative divider; WCAG exempts decoration.
]


def lum(h):
    h = h.lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    r, g, b = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    f = lambda c: c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)


def ratio(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def blocks(text):
    """Every top-level selector block, in source order."""
    out = []
    depth = 0
    start = None
    selector = None
    i = 0
    while i < len(text):
        c = text[i]
        if c == "{":
            if depth == 0:
                head = text[:i].rstrip()
                selector = head.split("\n")[-1].strip()
                start = i + 1
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                out.append((selector, text[start:i]))
        i += 1
    return out


def tokens(body):
    return {m.group(1): m.group(2).strip()
            for m in re.finditer(r"--color-([a-z0-9-]+)\s*:\s*(#[0-9a-fA-F]{3,8})\s*;", body)}



# --------------------------------------------------------------------------
# Colour blindness.
#
# Roughly one man in twelve is red-green colour blind, and dichromats keep
# luminance while losing hue -- so two colours that differ only in hue become
# the same colour. This section reports the semantic pairs, because one of them
# cannot be fixed by choosing better hexes and it is worth knowing which.
#
# WARNING vs ERROR is that pair. Orange and red are neighbours; every
# combination that keeps both readable on a dark surface collapses to under 1.5
# luminance separation for a deuteranope. The answer is not a different orange,
# it is WCAG 1.4.1: colour must not be the only signal. Every warning and error
# in this app carries an icon or a word as well -- "missed", "failed", "Past the
# budget" -- and that is what has to stay true, so this prints a reminder rather
# than a pass mark it cannot honestly give.
# --------------------------------------------------------------------------

DEUTAN = [[0.625, 0.375, 0.0], [0.70, 0.30, 0.0], [0.0, 0.30, 0.70]]
PROTAN = [[0.567, 0.433, 0.0], [0.558, 0.442, 0.0], [0.0, 0.242, 0.758]]

SEMANTIC_PAIRS = [
    ("primary", "secondary"),
    ("primary", "warning"),
    ("primary", "error"),
    ("secondary", "warning"),
    ("secondary", "error"),
    ("warning", "error"),
]

# Roles that live in the same (warm) hue family. Amber, orange and red are
# neighbours; a dichromat sees one colour there whichever hexes are chosen, so
# these pairs can only ever be told apart by an icon or a word. Reported, not
# failed -- failing them would be a gate nobody could ever satisfy.
WARM = {"primary", "warning", "error"}


def needs_cue(a, b, theme):
    # The customer portal is single-accent amber on purpose: primary and
    # secondary are the same colour by design, not by accident.
    if theme == ".portal-theme":
        return True
    return a in WARM and b in WARM


def _s2l(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def _l2s(c):
    c = max(0.0, min(1.0, c))
    return 12.92 * c if c <= 0.0031308 else 1.055 * (c ** (1 / 2.4)) - 0.055


def simulate(h, m):
    if m is None:
        return h
    h = h.lstrip("#")
    v = [_s2l(int(h[i:i + 2], 16) / 255) for i in (0, 2, 4)]
    out = [sum(m[r][c] * v[c] for c in range(3)) for r in range(3)]
    return "#%02x%02x%02x" % tuple(round(_l2s(c) * 255) for c in out)


def spread(a, b):
    ca = [int(a.lstrip("#")[i:i + 2], 16) for i in (0, 2, 4)]
    cb = [int(b.lstrip("#")[i:i + 2], 16) for i in (0, 2, 4)]
    return sum(abs(x - y) for x, y in zip(ca, cb))


def colour_blind_report(resolved):
    print("\n--- colour blindness: can these be told apart? ---")
    reminders = 0
    for theme, vals in resolved.items():
        notes = []
        for a, b in SEMANTIC_PAIRS:
            if a not in vals or b not in vals:
                continue
            worst = min(
                ((ratio(simulate(vals[a], m), simulate(vals[b], m)),
                  spread(simulate(vals[a], m), simulate(vals[b], m)), name)
                 for name, m in (("normal", None), ("deuteranopia", DEUTAN),
                                 ("protanopia", PROTAN))),
                key=lambda t: (t[0], t[1]))
            close = worst[0] < 1.35 and worst[1] < 90
            if close:
                cue = needs_cue(a, b, theme)
                tag = ("same hue family - needs an icon or a label"
                       if cue else "TOO CLOSE - pick different values")
                notes.append("    %-22s %-13s lum %.2f spread %3d  %s"
                             % (a + " vs " + b, worst[2], worst[0], worst[1], tag))
                if not cue:
                    reminders += 1
        print("  %s  %s" % (theme, "all pairs separable" if not notes else ""))
        for n in notes:
            print(n)
    return reminders


def main():
    text = io.open(CSS, encoding="utf-8", newline="").read().replace("\r\n", "\n")
    found = blocks(text)

    base = {}
    for selector, body in found:
        if selector.startswith("@theme"):
            base.update(tokens(body))

    # Themes, in the order a later one overrides an earlier one for the same
    # selector text. admin-light must inherit from admin, since in the DOM the
    # element carries both classes.
    themes = {}
    for selector, body in found:
        t = tokens(body)
        if not t:
            continue
        for name in (".portal-theme", ".tech-theme", ".admin-theme",
                     '.admin-theme[data-theme="light"]'):
            if selector == name:
                themes.setdefault(name, {}).update(t)

    resolved = {"(:root / @theme)": dict(base)}
    for name in (".portal-theme", ".tech-theme", ".admin-theme"):
        if name in themes:
            merged = dict(base)
            merged.update(themes[name])
            resolved[name] = merged
    light = '.admin-theme[data-theme="light"]'
    if light in themes:
        merged = dict(base)
        merged.update(themes.get(".admin-theme", {}))
        merged.update(themes[light])
        resolved[light] = merged

    failures = 0
    for theme, vals in resolved.items():
        lines = []
        for fg, bg, need, what in PAIRS:
            if fg not in vals or bg not in vals:
                continue
            r = ratio(vals[fg], vals[bg])
            if r < need:
                failures += 1
                lines.append("    FAIL %-46s %5.2f  need %.1f   %s on %s"
                             % (what, r, need, vals[fg], vals[bg]))
        print("%s  %s" % (theme, "OK" if not lines else "%d problem(s)" % len(lines)))
        for l in lines:
            print(l)
    unfixable = colour_blind_report(resolved)
    print("\n%d failing pair(s), %d colour pair(s) needing different values"
          % (failures, unfixable))
    return 1 if failures or unfixable else 0


if __name__ == "__main__":
    sys.exit(main())
