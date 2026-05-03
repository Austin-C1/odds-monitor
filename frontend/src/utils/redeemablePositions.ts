import type { AccountPosition, RedeemablePositionsSummary } from '../types'

type ParsedDecimal = {
  scale: number
  value: bigint
}

const DECIMAL_PATTERN = /^-?\d+(?:\.\d+)?$/

function parseDecimal(value: string): ParsedDecimal {
  const normalized = value.trim()
  if (!DECIMAL_PATTERN.test(normalized)) {
    return { value: 0n, scale: 0 }
  }

  const negative = normalized.startsWith('-')
  const unsigned = negative ? normalized.slice(1) : normalized
  const [integerPart, fractionPart = ''] = unsigned.split('.')
  const digits = `${integerPart}${fractionPart}`.replace(/^0+(?=\d)/, '') || '0'
  const parsed = BigInt(digits)

  return {
    value: negative ? -parsed : parsed,
    scale: fractionPart.length
  }
}

function powerOfTen(scale: number): bigint {
  let result = 1n
  for (let index = 0; index < scale; index += 1) {
    result *= 10n
  }
  return result
}

function formatDecimal(value: bigint, scale: number): string {
  if (value === 0n) {
    return '0'
  }

  const negative = value < 0n
  const absolute = negative ? -value : value
  const digits = absolute.toString().padStart(scale + 1, '0')
  const integerPart = scale === 0 ? digits : digits.slice(0, -scale)
  const fractionPart = scale === 0 ? '' : digits.slice(-scale).replace(/0+$/, '')
  const sign = negative ? '-' : ''

  return fractionPart ? `${sign}${integerPart}.${fractionPart}` : `${sign}${integerPart}`
}

function sumDecimalStrings(values: string[]): string {
  let total = 0n
  let totalScale = 0

  values.forEach((value) => {
    const parsed = parseDecimal(value)
    if (parsed.scale > totalScale) {
      total *= powerOfTen(parsed.scale - totalScale)
      totalScale = parsed.scale
    }

    total += parsed.value * powerOfTen(totalScale - parsed.scale)
  })

  return formatDecimal(total, totalScale)
}

export function buildRedeemablePositionsSummary(
  positions: AccountPosition[],
  accountId?: number
): RedeemablePositionsSummary {
  const redeemablePositions = positions
    .filter((position) => position.redeemable)
    .filter((position) => accountId === undefined || position.accountId === accountId)

  return {
    totalCount: redeemablePositions.length,
    totalValue: sumDecimalStrings(redeemablePositions.map((position) => position.quantity)),
    positions: redeemablePositions.map((position) => ({
      accountId: position.accountId,
      accountName: position.accountName,
      marketId: position.marketId,
      marketTitle: position.marketTitle,
      side: position.side,
      outcomeIndex: position.outcomeIndex ?? 0,
      quantity: position.quantity,
      value: position.quantity
    }))
  }
}
