-- ============================================
-- 修复回测恢复逻辑：将 last_processed_trade_index 默认值改为 NULL
-- ============================================
-- 问题：新建任务的 last_processed_trade_index 默认值为 0，导致被误判为恢复任务
-- 解决：将默认值改为 NULL，并将现有新任务的 0 值改为 NULL

-- 1. 将现有新任务（status='PENDING' 且 last_processed_trade_index=0）的索引值改为 NULL
UPDATE backtest_task 
SET last_processed_trade_index = NULL 
WHERE status = 'PENDING' AND last_processed_trade_index = 0;

-- 2. 修改字段定义，允许 NULL 并设置默认值为 NULL
ALTER TABLE backtest_task 
  MODIFY COLUMN last_processed_trade_index INT DEFAULT NULL COMMENT '最后处理的交易索引（用于中断恢复）';

