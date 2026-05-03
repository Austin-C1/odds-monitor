# Polymarket 跟单系统前端

## 技术栈

- **框架**: React 18 + TypeScript
- **构建工具**: Vite
- **UI 库**: Ant Design + Ant Design Mobile
- **HTTP 客户端**: axios
- **状态管理**: Zustand
- **路由**: React Router
- **以太坊库**: ethers.js

## 功能特性

- ✅ 账户管理（通过私钥导入，支持多账户）
- ✅ Leader 管理（被跟单者管理）
- ✅ 跟单配置管理
- ✅ 订单管理
- ✅ 统计信息
- ✅ 移动端适配

## 开发

```bash
# 安装依赖
npm install

# 启动开发服务器
npm run dev

# 构建生产版本
npm run build
```

## 项目结构

```
frontend/
├── src/
│   ├── components/      # 公共组件
│   │   └── Layout.tsx   # 布局组件（支持移动端）
│   ├── pages/           # 页面组件
│   │   ├── AccountList.tsx
│   │   ├── AccountImport.tsx
│   │   ├── LeaderList.tsx
│   │   ├── LeaderAdd.tsx
│   │   ├── ConfigPage.tsx
│   │   ├── OrderList.tsx
│   │   └── Statistics.tsx
│   ├── services/        # API 服务
│   │   └── api.ts
│   ├── store/           # 状态管理
│   │   └── accountStore.ts
│   ├── types/           # TypeScript 类型定义
│   │   └── index.ts
│   ├── utils/           # 工具函数
│   │   └── ethers.ts
│   ├── styles/          # 样式文件
│   │   └── index.css
│   ├── App.tsx          # 根组件
│   └── main.tsx         # 入口文件
├── index.html
├── package.json
├── tsconfig.json
└── vite.config.ts
```

## 移动端适配

- 使用 `react-responsive` 检测设备类型
- 移动端使用 Ant Design Mobile 组件
- 响应式布局（移动端 < 768px，桌面端 >= 768px）
- 触摸友好的按钮尺寸（>= 44x44px）

