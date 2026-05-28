# 全平台赔率监控

全平台赔率监控用于统一查看 Pinnacle 和 Crown 的足球盘口，合并展示同一场比赛的盘口变化，并支持 Telegram 通知和 Crown 自动投注测试。

## 当前能力

- 一场比赛只显示一次，Pinnacle 和 Crown 盘口合并到同一个详情页。
- 采集让球盘和大小球盘口。
- 支持按球队、联赛、平台搜索，支持筛选单平台、双平台、疑似缺盘口等状态。
- 告警记录可作为投注信号来源。
- Crown 投注通过 AdsPower Profile 执行，并在投注历史确认后写入下注成功记录。
- 系统更新页支持从 GitHub Release 检查和安装更新包。

## 本机启动

双击桌面快捷方式 `启动全平台赔率监控`，或在项目目录运行：

```powershell
.\launch-odds-monitor.ps1
```

默认入口：

```text
http://127.0.0.1:18881/login
```

## GitHub 远程更新

1. 在 `config/update.json` 中配置：

```json
{
  "githubRepo": "你的 GitHub 用户名/odds-monitor",
  "releaseApiUrl": "",
  "githubToken": ""
}
```

2. 构建更新包和完整安装包：

```powershell
.\build-odds-monitor-update-package.ps1
.\build-odds-monitor-full-package.ps1
```

3. 将生成的 `odds-monitor-update-v版本号.zip` 和 `odds-monitor-full-v版本号.zip` 上传到 GitHub Release。

更新包只覆盖程序文件，不覆盖本地配置、账号数据、数据库、日志、备份和更新缓存。
完整安装包包含本地 Java 运行环境，适合新电脑或缺少运行环境的用户。

## 上传前检查

```powershell
$env:JAVA_HOME='C:\Users\kesul\Desktop\全平台赔率监控\.tools\jdk-17.0.18+8'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\backend\gradlew.bat -p backend test

cd frontend
npm test
npm run build
```
