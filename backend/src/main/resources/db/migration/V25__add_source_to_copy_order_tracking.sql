-- ============================================
-- V25: 添加订单来源字段到跟单订单跟踪表
-- 用于记录订单是从哪个数据源接收到的（activity-ws 或 onchain-ws）
-- ============================================

-- 添加订单来源字段
ALTER TABLE copy_order_tracking
ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'unknown' COMMENT '订单来源：activity-ws（Polymarket WebSocket）、onchain-ws（OnChain WebSocket）';

-- 对于已有数据，设置为默认值 unknown（不影响现有功能）
-- 新创建的记录会在创建时自动填充此字段

