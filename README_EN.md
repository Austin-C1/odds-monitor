# Crown Odds Monitor and Auto Betting

Crown Odds Monitor and Auto Betting is a local tool for collecting Crown football odds, tracking market changes, sending Telegram notifications, and executing/verifying Crown bets through AdsPower profiles.

## Features

- Crown odds monitoring.
- Match, league, handicap, and total market views.
- Odds-change alerts and Telegram notification templates.
- Crown account status checks through AdsPower profiles.
- Auto-betting intent creation from monitor alerts.
- Crown execution verification through betting history.
- Local update packaging and GitHub Release update checks.

## Run Locally

```powershell
.\launch-odds-monitor.ps1
```

Default URL:

```text
http://127.0.0.1:18881/login
```

## Verification

```powershell
$env:JAVA_HOME='C:\Users\kesul\Desktop\全平台赔率监控\.tools\jdk-17.0.18+8'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\backend\gradlew.bat -p backend test

cd frontend
npm test
npm run build
```
