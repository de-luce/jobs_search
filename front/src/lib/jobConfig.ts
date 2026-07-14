/** 关键词 JSON 数组 ↔ 逗号分隔展示文本 */
export function parseKeywordsFromDb(raw?: string): string {
  if (!raw) return ''
  const tokens = parseMultiTokensFromDb(raw)
  return tokens.join(', ')
}

export function serializeKeywordsForDb(display?: string): string {
  const raw = (display || '').trim()
  if (!raw) return '[]'
  const norm = raw.replace(/，/g, ',')
  const tokens = norm
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
  return JSON.stringify(tokens)
}

export function parseSingleTokenFromDb(raw?: string): string {
  const tokens = parseMultiTokensFromDb(raw)
  return tokens[0] || ''
}

/**
 * 解析库里的多选字段：支持 JSON 数组、[a,b] 括号列表、逗号分隔。
 * 注意：[20-50K,10-20K] 不是合法 JSON，不能只依赖 JSON.parse。
 */
export function parseMultiTokensFromDb(raw?: string): string[] {
  if (!raw) return []
  let t = raw.trim()
  if (!t) return []

  if (t.startsWith('[') && t.endsWith(']')) {
    try {
      const arr = JSON.parse(t)
      if (Array.isArray(arr)) {
        return arr.map((v) => String(v ?? '').trim()).filter(Boolean)
      }
    } catch {
      // 去掉一层括号后按逗号切分，兼容 [20-50K,10-20K] 这类非 JSON 列表
      t = t.slice(1, -1).trim()
    }
  }

  if (!t) return []
  return t
    .replace(/，/g, ',')
    .split(',')
    .map((s) => s.trim().replace(/^["']|["']$/g, ''))
    .filter(Boolean)
}
