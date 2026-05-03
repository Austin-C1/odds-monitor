import { describe, expect, it } from 'vitest'

import { getApprovalDetailDisplay } from '../src/utils/accountSetupApproval'

describe('getApprovalDetailDisplay', () => {
  it('maps known backend keys to translation keys', () => {
    expect(getApprovalDetailDisplay('PUSD_TO_CTF', 'notApproved')).toMatchObject({
      labelKey: 'accountSetup.approvalDetails.PUSD_TO_CTF',
      valueKey: 'accountSetup.approvalDetails.notApproved',
      fallbackLabel: 'PUSD_TO_CTF'
    })

    expect(getApprovalDetailDisplay('CTF_CONTRACT', 'unlimited')).toMatchObject({
      labelKey: 'accountSetup.approvalDetails.CTF_CONTRACT',
      valueKey: 'accountSetup.approvalDetails.unlimited'
    })
  })

  it('treats zero allowance as not approved', () => {
    expect(getApprovalDetailDisplay('CTF_TO_EXCHANGE', '0.000000')).toMatchObject({
      valueKey: 'accountSetup.approvalDetails.notApproved',
      fallbackValue: '0.000000'
    })
  })

  it('keeps positive numeric allowance visible', () => {
    expect(getApprovalDetailDisplay('NEG_RISK_ADAPTER', '12.500000')).toMatchObject({
      labelKey: 'accountSetup.approvalDetails.NEG_RISK_ADAPTER',
      fallbackValue: '12.500000',
      tagColor: 'processing'
    })
  })

  it('falls back to raw text for unknown keys and statuses', () => {
    expect(getApprovalDetailDisplay('UNKNOWN_KEY', 'pending-review')).toMatchObject({
      fallbackLabel: 'UNKNOWN_KEY',
      fallbackValue: 'pending-review',
      tagColor: 'default'
    })
  })
})
