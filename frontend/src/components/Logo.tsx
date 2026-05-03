import React from 'react'

interface LogoProps {
  /**
   * Logo 尺寸
   * @default 'normal'
   */
  size?: 'small' | 'normal' | 'large'
  /**
   * 是否只显示图标（不显示文字）
   * @default false
   */
  iconOnly?: boolean
  /**
   * 是否使用深色模式（用于深色背景）
   * @default false
   */
  darkMode?: boolean
  /**
   * 自定义样式类名
   */
  className?: string
  /**
   * 自定义样式
   */
  style?: React.CSSProperties
}

/**
 * 全平台赔率监控 Logo 组件
 * 
 * 设计理念：
 * - Hermes（赫尔墨斯）是希腊神话中的信使神，代表快速传递和连接
 * - 结合交易元素（箭头、连接线）体现跟单交易的核心功能
 * - 现代简洁的设计风格，适配深色和浅色背景
 */
const Logo: React.FC<LogoProps> = ({ 
  size = 'normal', 
  iconOnly = false,
  darkMode = false,
  className = '',
  style = {}
}) => {
  // 根据尺寸确定图标大小
  const iconSizes = {
    small: 24,
    normal: 32,
    large: 48
  }
  
  // 根据尺寸确定文字大小
  const textSizes = {
    small: 14,
    normal: 18,
    large: 24
  }
  
  const iconSize = iconSizes[size]
  const textSize = textSizes[size]
  
  // 根据深色模式选择颜色
  const gradientColors = darkMode 
    ? { start: '#69c0ff', end: '#b37feb' } // 深色背景使用较亮的颜色
    : { start: '#1890ff', end: '#722ed1' } // 浅色背景使用标准颜色
  
  const textColor = darkMode ? '#fff' : 'inherit'
  
  return (
    <div 
      className={`odds-monitor-logo ${className}`}
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: '8px',
        ...style
      }}
    >
      {/* Logo 图标 */}
      <svg
        width={iconSize}
        height={iconSize}
        viewBox="0 0 64 64"
        fill="none"
        xmlns="http://www.w3.org/2000/svg"
        style={{ flexShrink: 0 }}
      >
        {/* 渐变定义 */}
        <defs>
          <linearGradient id={`logoGradient-${darkMode ? 'dark' : 'light'}`} x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor={gradientColors.start} />
            <stop offset="100%" stopColor={gradientColors.end} />
          </linearGradient>
        </defs>
        
        {/* 主图标设计：双箭头连接，代表跟单交易 */}
        {/* 左侧箭头（指向中心） */}
        <path
          d="M 16 32 L 8 24 L 8 40 Z"
          fill={`url(#logoGradient-${darkMode ? 'dark' : 'light'})`}
        />
        
        {/* 中心连接线（代表跟单连接） */}
        <line
          x1="20"
          y1="32"
          x2="44"
          y2="32"
          stroke={`url(#logoGradient-${darkMode ? 'dark' : 'light'})`}
          strokeWidth="3"
          strokeLinecap="round"
        />
        
        {/* 右侧箭头（指向中心） */}
        <path
          d="M 48 32 L 56 24 L 56 40 Z"
          fill={`url(#logoGradient-${darkMode ? 'dark' : 'light'})`}
        />
        
        {/* 中心圆点（代表交易节点/数据同步点） */}
        <circle
          cx="32"
          cy="32"
          r="5"
          fill={`url(#logoGradient-${darkMode ? 'dark' : 'light'})`}
        />
        
        {/* 装饰性数据流弧线（代表实时数据同步） */}
        <path
          d="M 20 20 Q 32 14 44 20"
          stroke={`url(#logoGradient-${darkMode ? 'dark' : 'light'})`}
          strokeWidth="2"
          fill="none"
          opacity="0.5"
          strokeLinecap="round"
        />
        <path
          d="M 20 44 Q 32 50 44 44"
          stroke={`url(#logoGradient-${darkMode ? 'dark' : 'light'})`}
          strokeWidth="2"
          fill="none"
          opacity="0.5"
          strokeLinecap="round"
        />
      </svg>
      
      {/* Logo 文字 */}
      {!iconOnly && (
        <span
          style={{
            fontSize: `${textSize}px`,
            fontWeight: 'bold',
            background: darkMode 
              ? 'linear-gradient(135deg, #69c0ff 0%, #b37feb 100%)'
              : 'linear-gradient(135deg, #1890ff 0%, #722ed1 100%)',
            WebkitBackgroundClip: 'text',
            WebkitTextFillColor: 'transparent',
            backgroundClip: 'text',
            letterSpacing: '0.5px',
            color: textColor
          }}
        >
          全平台赔率监控
        </span>
      )}
    </div>
  )
}

export default Logo

