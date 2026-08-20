/* Minimal RFC-4180-ish CSV parser: handles quoted fields, embedded commas and
   newlines, and doubled "" escapes. Returns an array of string arrays.

   Shared by the subscriber import and the bank statement import. Both parse in
   the browser on purpose: the operator has to see the parse and fix the column
   mapping before anything reaches the server, and a bank CSV is never the shape
   you expected. */
export function parseCsv(text) {
  const rows = []
  let row = []
  let field = ''
  let inQuotes = false
  const s = String(text).replace(/\r\n?/g, '\n')
  for (let i = 0; i < s.length; i++) {
    const c = s[i]
    if (inQuotes) {
      if (c === '"') {
        if (s[i + 1] === '"') { field += '"'; i++ } else inQuotes = false
      } else field += c
    } else if (c === '"') inQuotes = true
    else if (c === ',') { row.push(field); field = '' }
    else if (c === '\n') { row.push(field); rows.push(row); row = []; field = '' }
    else field += c
  }
  if (field.length || row.length) { row.push(field); rows.push(row) }
  return rows
}

/* Semicolon-separated files are common from European-locale bank exports, where
   the comma is the decimal separator. Sniffed from the header line rather than
   configured, because an operator downloading their own statement has no idea
   which one they got. */
export function sniffDelimiter(text) {
  const firstLine = String(text).split(/\r?\n/, 1)[0] || ''
  const commas = (firstLine.match(/,/g) || []).length
  const semis = (firstLine.match(/;/g) || []).length
  const tabs = (firstLine.match(/\t/g) || []).length
  if (semis > commas && semis >= tabs) return ';'
  if (tabs > commas && tabs > semis) return '\t'
  return ','
}

/* parseCsv, but for a file that turned out not to use commas. The delimiter is
   swapped for a comma outside quoted fields, which is cheaper and less
   error-prone than parameterising the parser. */
export function parseDelimited(text, delimiter) {
  if (!delimiter || delimiter === ',') return parseCsv(text)
  const s = String(text)
  let out = ''
  let inQuotes = false
  for (let i = 0; i < s.length; i++) {
    const c = s[i]
    if (c === '"') inQuotes = !inQuotes
    out += (!inQuotes && c === delimiter) ? ',' : c
  }
  return parseCsv(out)
}
