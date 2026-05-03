const overUnderMarketTypes = new Set(['totals', 'first_half_totals', 'points', 'rebounds', 'assists', 'threes', 'blocks', 'steals'])
const mainGameMarketTypes = new Set(['moneyline', 'spreads', 'totals'])

export const displayMarketTitle = (value: string) => value.replace(/\bO\/U\b/gi, '\u5927\u5c0f')

export const displayOutcomeName = (name: string, marketTitle: string, marketType: string) => {
  if (!overUnderMarketTypes.has(marketType.toLowerCase()) && !/O\/U/i.test(marketTitle)) return name
  const normalized = name.trim().toLowerCase()
  if (normalized === 'yes' || normalized === 'over') return '\u5927'
  if (normalized === 'no' || normalized === 'under') return '\u5c0f'
  return name
}

export const isMainGameMarketType = (marketType: string) => mainGameMarketTypes.has(marketType.trim().toLowerCase())
