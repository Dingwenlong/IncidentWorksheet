const segmenter = new Intl.Segmenter('zh-CN', { granularity: 'word' })

function excerpt(raw, fromEnd) {
  const candidates = [...raw.matchAll(/[^。！？!?；;\r\n]+[。！？!?；;]*/gu)]
    .map((match) => ({
      sentence: match[0].trim(),
      start: match.index + match[0].length - match[0].trimStart().length,
    }))
    .filter(({ sentence }) => sentence)
  if (!candidates.length && raw.trim()) {
    candidates.push({ sentence: raw.trim(), start: raw.length - raw.trimStart().length })
  }
  const chosen = fromEnd ? candidates.at(-1) : candidates[0]
  if (!chosen) return { sentence: null, start: null, end: null, keywords: [], selection: 'EMPTY' }

  const { sentence, start } = chosen
  const keywords = [...new Set(
    [...segmenter.segment(sentence)].filter((part) => part.isWordLike).map((part) => part.segment),
  )].slice(0, 5)
  return {
    sentence, start, end: start + sentence.length, keywords,
    selection: !keywords.length ? 'FALLBACK' : candidates.length === 1 ? 'SINGLE' : 'RANKED',
  }
}

// Mock excerpts exercise the real response shape and highlighting. jieba/TF-IDF/TextRank run in Java.
export function summarize(order) {
  return Object.fromEntries(['TFIDF', 'TEXTRANK'].map((algorithm) => {
    const fromEnd = algorithm === 'TEXTRANK'
    return [algorithm, {
      algorithm,
      algorithmVersion: 'mock-1',
      dictionaryVersion: 'mock-1',
      idfVersion: fromEnd ? null : 'mock-1',
      textVersion: order.textVersion,
      fault: excerpt(order.faultText, fromEnd),
      action: excerpt(order.actionText, fromEnd),
    }]
  }))
}
