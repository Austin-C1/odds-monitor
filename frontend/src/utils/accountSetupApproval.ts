const APPROVAL_LABEL_KEYS: Record<string, string> = {
  PUSD_TO_CTF: 'accountSetup.approvalDetails.PUSD_TO_CTF',
  CTF_CONTRACT: 'accountSetup.approvalDetails.CTF_CONTRACT',
  CTF_TO_EXCHANGE: 'accountSetup.approvalDetails.CTF_TO_EXCHANGE',
  CTF_EXCHANGE: 'accountSetup.approvalDetails.CTF_EXCHANGE',
  CTF_TO_NEG_RISK_EXCHANGE: 'accountSetup.approvalDetails.CTF_TO_NEG_RISK_EXCHANGE',
  NEG_RISK_EXCHANGE: 'accountSetup.approvalDetails.NEG_RISK_EXCHANGE',
  NEG_RISK_ADAPTER: 'accountSetup.approvalDetails.NEG_RISK_ADAPTER'
}

const APPROVAL_VALUE_KEYS: Record<string, string> = {
  approved: 'accountSetup.approvalDetails.approved',
  notapproved: 'accountSetup.approvalDetails.notApproved',
  unlimited: 'accountSetup.approvalDetails.unlimited'
}

export interface ApprovalDetailDisplay {
  labelKey?: string
  fallbackLabel: string
  valueKey?: string
  fallbackValue: string
  tagColor: 'success' | 'default' | 'processing'
}

const parseNumericAllowance = (value: string): number | null => {
  if (!/^-?\d+(\.\d+)?$/.test(value)) {
    return null
  }

  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

export const getApprovalDetailDisplay = (
  approvalKey: string,
  approvalValue: string | null | undefined
): ApprovalDetailDisplay => {
  const normalizedValue = approvalValue?.trim() ?? ''
  const numericAllowance = parseNumericAllowance(normalizedValue)
  const normalizedStatusKey = normalizedValue.toLowerCase()

  if (normalizedStatusKey in APPROVAL_VALUE_KEYS) {
    const valueKey = APPROVAL_VALUE_KEYS[normalizedStatusKey]
    return {
      labelKey: APPROVAL_LABEL_KEYS[approvalKey],
      fallbackLabel: approvalKey,
      valueKey,
      fallbackValue: normalizedValue,
      tagColor: normalizedStatusKey === 'notapproved' ? 'default' : 'success'
    }
  }

  if (numericAllowance !== null && numericAllowance <= 0) {
    return {
      labelKey: APPROVAL_LABEL_KEYS[approvalKey],
      fallbackLabel: approvalKey,
      valueKey: APPROVAL_VALUE_KEYS.notapproved,
      fallbackValue: normalizedValue,
      tagColor: 'default'
    }
  }

  if (numericAllowance !== null) {
    return {
      labelKey: APPROVAL_LABEL_KEYS[approvalKey],
      fallbackLabel: approvalKey,
      fallbackValue: normalizedValue,
      tagColor: 'processing'
    }
  }

  return {
    labelKey: APPROVAL_LABEL_KEYS[approvalKey],
    fallbackLabel: approvalKey,
    fallbackValue: normalizedValue || '-',
    tagColor: 'default'
  }
}
