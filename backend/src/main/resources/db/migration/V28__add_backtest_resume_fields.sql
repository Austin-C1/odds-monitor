-- ============================================
-- 回测功能恢复字段添加
-- ============================================

-- 添加恢复相关字段到回测任务表
ALTER TABLE backtest_task 
  ADD COLUMN last_processed_trade_time BIGINT DEFAULT NULL COMMENT '最后处理的交易时间（用于中断恢复）',
  ADD COLUMN last_processed_trade_index INT DEFAULT 0 COMMENT '最后处理的交易索引（用于中断恢复）',
  ADD COLUMN processed_trade_count INT DEFAULT 0 COMMENT '已处理的交易数量（用于显示真实进度）';

-- 添加索引以优化查询性能
ALTER TABLE backtest_task
  ADD INDEX idx_last_processed_trade_time (last_processed_trade_time);

