// Preserve the service document, including any secret expressions, while adding
// the persistent storage required by the runner's stop markers.
export function persistentRunnerCompose(compose) {
  if (typeof compose !== 'string')
    throw new Error('Cannot verify runner storage: missing service Compose.')
  const lines = compose.split('\n')
  const start = lines.findIndex(line => /^\s+runner:\s*$/.test(line))
  if (start < 0)
    throw new Error('Cannot verify runner storage: runner service not found.')
  const indent = lines[start].search(/\S/)
  let volumeIndent
  let volumeStart
  for (let index = start + 1; index < lines.length; index++) {
    const line = lines[index]
    if (!line.trim() || line.trimStart().startsWith('#'))
      continue
    if (line.search(/\S/) <= indent)
      break
    if (volumeIndent === undefined) {
      if (/^\s+volumes:\s*$/.test(line)) {
        volumeIndent = line.search(/\S/)
        volumeStart = index
      }
      continue
    }
    if (line.search(/\S/) <= volumeIndent)
      break
    const mount = line.match(/^(\s*-\s*['"]?[^'"\s]+:\/runner-state)(?::(ro|rw))?(['"]?\s*(?:#.*)?)$/)
    if (!mount) {
      if (line.includes('/runner-state'))
        throw new Error('Cannot verify runner storage: unsupported runner-state mount.')
      continue
    }
    if (mount[2] === 'ro')
      lines[index] = `${mount[1]}:rw${mount[3]}`
    return lines.join('\n')
  }
  if (volumeStart === undefined)
    throw new Error('Cannot verify runner storage: runner volumes block not found.')
  const declarations = lines.findIndex(line => /^volumes:\s*$/.test(line))
  if (declarations < 0)
    throw new Error('Cannot verify runner storage: top-level volumes block not found.')
  let declared = false
  for (let index = declarations + 1; index < lines.length; index++) {
    if (/^\S/.test(lines[index]))
      break
    if (lines[index].startsWith('  runner-state:'))
      declared = true
  }
  if (!declared)
    lines.splice(declarations + 1, 0, '  runner-state:')
  // Locate the service block again in case top-level volumes precede services.
  const offset = !declared && declarations < volumeStart ? 1 : 0
  lines.splice(volumeStart + offset + 1, 0, `${' '.repeat(volumeIndent + 2)}- runner-state:/runner-state`)
  return lines.join('\n')
}
