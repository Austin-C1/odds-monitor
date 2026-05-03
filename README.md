# 全平台赔率监控

全平台赔率监控用于统一查看平博、皇冠和 Polymarket 的足球盘口，并把同一场比赛的盘口变化合并展示和通知。

## 当前能力

- 一场比赛只显示一次，平博、皇冠、Polymarket 盘口合并到同一个详情页。
- 平博和皇冠采集让球盘、大小球；不采集、不展示胜平负。
- Polymarket 保留胜平负概率盘口，并参与同场比赛匹配。
- 支持按球队、联赛、平台搜索，支持筛选单平台、三平台、疑似缺盘口等状态。
- Telegram 通知按同一场比赛和同一盘口合并推送。
- 本机一键启动，默认入口为 `http://127.0.0.1:18881/login`。
- 系统更新页支持从 GitHub Release 检查和安装更新包。

## 本机启动

双击桌面快捷方式 `启动全平台赔率监控`，或在项目目录运行：

```powershell
.\launch-odds-monitor.cmd
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

2. 构建更新包：

```powershell
.\build-odds-monitor-update-package.ps1
```

3. 将生成的 `odds-monitor-update-v版本号.zip` 上传到 GitHub Release。
4. 其他用户在系统里的“系统更新”页面点击“检查更新”和“立即更新”。

更新包只覆盖程序文件，不覆盖本地配置、账号数据、数据库、日志、备份和更新缓存。

GitHub 仓库路径需要使用英文、数字或短横线；项目显示名称仍使用“全平台赔率监控”。

## 上传前检查

推荐在上传或发布前运行：

```powershell
$env:JAVA_HOME='C:\Users\kesul\Desktop\全平台赔率监控\.tools\jdk-17.0.18+8'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\backend\gradlew.bat -p backend test
cd frontend
npm test
npm run build
```
