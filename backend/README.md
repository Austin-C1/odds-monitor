# Polymarket Bot Backend

Polymarket 预测市场机器人后端服务

## 功能特性

- ✅ 封装 Polymarket CLOB API（订单操作、市场数据、交易数据）
- ✅ 封装 Polymarket Gamma API（市场、事件、系列查询）
- ✅ WebSocket 转发服务（转发前端 WebSocket 连接到 Polymarket RTDS）
- ✅ 统一 API 响应格式
- ✅ 分类验证（仅支持 sports 和 crypto）
- ✅ 工具类支持（安全转换、数学运算、HTTP 客户端等）

## 技术栈

- Spring Boot 3.2.0
- Kotlin 1.9.20
- Retrofit 2.9.0
- OkHttp 4.12.0
- Java-WebSocket 1.5.4
- MySQL 8.2.0
- Flyway（数据库迁移）

## 项目结构

```
backend/
├── src/
│   ├── main/
│   │   ├── kotlin/com/wrbug/polymarketbot/
│   │   │   ├── api/              # API 接口定义
│   │   │   ├── config/           # 配置类
│   │   │   ├── controller/      # 控制器
│   │   │   ├── dto/              # 数据传输对象
│   │   │   ├── service/          # 服务层
│   │   │   ├── util/             # 工具类
│   │   │   └── websocket/        # WebSocket 处理
│   │   └── resources/
│   │       ├── application.properties
│   │       └── db/migration/    # Flyway 迁移脚本
│   └── test/
└── build.gradle.kts
```

## 配置说明

### 环境变量

- `DB_USERNAME`: 数据库用户名（默认: root）
- `DB_PASSWORD`: 数据库密码（默认: password）
- `SERVER_PORT`: 服务器端口（默认: 8000）
- `CRYPTO_SECRET_KEY`: 加密密钥（用于加密存储私钥和 API Key，建议设置）

### application.properties

主要配置项：
- 数据库连接配置
- Polymarket API 地址配置
- WebSocket 地址配置

## API 接口

### 市场相关

- `POST /api/markets/list` - 获取市场列表
- `POST /api/markets/detail` - 获取市场详情
- `POST /api/markets/search` - 搜索市场
- `POST /api/markets/sports` - 获取体育市场
- `POST /api/markets/crypto` - 获取加密货币市场

### WebSocket

- `WS /ws/polymarket` - WebSocket 连接端点（转发到 Polymarket RTDS）

## 响应格式

所有接口统一返回以下格式：

```json
{
  "code": 0,
  "data": {},
  "msg": ""
}
```

- `code`: 0 表示成功，非 0 表示失败
- `data`: 响应数据
- `msg`: 响应消息

## 错误码

- `0`: 成功
- `1001-1999`: 参数错误
- `2001-2999`: 认证/权限错误
- `3001-3999`: 资源不存在
- `4001-4999`: 业务逻辑错误
- `5001-5999`: 服务器内部错误

## 运行

```bash
# 构建项目
./gradlew build

# 运行应用
./gradlew bootRun
```

## 注意事项

1. 仅支持 Polymarket 平台
2. 仅支持 `sports` 和 `crypto` 两个分类
3. WebSocket 转发需要配置正确的 Polymarket RTDS 地址
4. 生产环境需要配置正确的 CORS 策略

