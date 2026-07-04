import { ApiOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { Button, Space } from 'antd'
import { CrownAccountModal } from './crown-betting/CrownAccountModal'
import { CrownAccountSummary, CrownAccountTable } from './crown-betting/CrownAccountTable'
import { CrownAutomationControls } from './crown-betting/CrownAutomationControls'
import { useCrownAccounts } from './crown-betting/useCrownAccounts'
import { useCrownAutomation } from './crown-betting/useCrownAutomation'
import { PageShell } from './PageShell'

const CrownBetting = () => {
  const {
    accounts,
    accountsRef,
    modalOpen,
    setModalOpen,
    adsPowerStatus,
    adsPowerChecking,
    totalBalance,
    abnormalCount,
    boundProfileCount,
    enabledBettingAccounts,
    updateAccount,
    checkAccount,
    checkAdsPowerStatus,
    openAdsPowerProfile,
    addAccount,
    deleteAccount,
    refreshAccounts,
  } = useCrownAccounts()
  const automation = useCrownAutomation({
    accountsRef,
    enabledBettingAccounts,
  })

  return (
    <PageShell
      title="皇冠投注"
      description="监控保持本地浏览器直连，投注检测和执行只走 AdsPower 环境。"
      actions={(
        <Space wrap className="page-toolbar-group">
          <Button icon={<ApiOutlined />} loading={adsPowerChecking} onClick={checkAdsPowerStatus}>
            检测 AdsPower
          </Button>
          <Button icon={<ReloadOutlined />} onClick={refreshAccounts} disabled={accounts.length === 0}>
            刷新状态
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>
            添加皇冠账号
          </Button>
        </Space>
      )}
      className="crown-betting-page"
    >

      <CrownAccountSummary
        accounts={accounts}
        totalBalance={totalBalance}
        abnormalCount={abnormalCount}
        boundProfileCount={boundProfileCount}
        adsPowerStatus={adsPowerStatus}
      />

      <CrownAccountTable
        accounts={accounts}
        updateAccount={updateAccount}
        checkAccount={checkAccount}
        openAdsPowerProfile={openAdsPowerProfile}
        deleteAccount={deleteAccount}
      />

      <CrownAutomationControls
        settings={automation.automationSettings}
        updateAutomationSetting={automation.updateAutomationSetting}
        saveAutomationSettings={automation.saveAutomationSettings}
        executionRunning={automation.executionRunning}
        enabledBettingAccounts={enabledBettingAccounts}
        currentExecutionSignal={automation.currentExecutionSignal}
        currentExecutionSignalQueueItem={automation.currentExecutionSignalQueueItem}
        currentExecutionSignalLabel={automation.currentExecutionSignalLabel}
        currentExecutionStep={automation.currentExecutionStep}
        signalCandidates={automation.signalCandidates}
        executionPlan={automation.executionPlan}
        executionAccountTagColor={automation.executionAccountTagColor}
        executionAccountTagLabel={automation.executionAccountTagLabel}
      />

      <CrownAccountModal
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onAdd={addAccount}
      />
    </PageShell>
  )
}

export default CrownBetting
