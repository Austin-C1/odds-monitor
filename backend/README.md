# Odds Monitor Backend

全平台赔率监控后端服务。

## 功能

- 赔率监控数据源配置、状态、联赛筛选和比赛看板。
- Pinnacle 和 Crown 盘口数据采集。
- 自动投注信号判断、执行记录和下注成功记录。
- AdsPower 本地 API 检测、启动 Profile、读取 Crown 登录状态和余额。
- 通知配置、通知模板、系统配置、更新和登录鉴权。

## 技术栈

- Spring Boot 3.2.0
- Kotlin 1.9.20
- OkHttp 4.12.0
- Playwright 1.49.0
- Jsoup 1.17.2
- MySQL 8.2.0
- Flyway

## 主要接口

- `POST /api/odds-monitor/dashboard`
- `POST /api/odds-monitor/data-sources/configs/list`
- `POST /api/odds-monitor/data-sources/configs/save`
- `POST /api/auto-betting/signals/odds-monitor`
- `POST /api/auto-betting/intents/recent`
- `POST /api/auto-betting/intents/verified-placed`
- `POST /api/auto-betting/intents/{intentId}/execute-crown`
- `POST /api/auto-betting/adspower/status`
- `POST /api/auto-betting/adspower/start-profile`
- `POST /api/auto-betting/adspower/crown-session`

## 运行

```powershell
$env:JAVA_HOME='C:\Users\kesul\Desktop\全平台赔率监控\.tools\jdk-17.0.18+8'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat test
.\gradlew.bat bootRun
```
