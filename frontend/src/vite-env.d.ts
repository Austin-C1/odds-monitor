/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_URL?: string
  readonly VITE_WS_URL?: string
  readonly VITE_APP_VERSION?: string
  readonly VITE_APP_GIT_TAG?: string
  readonly VITE_APP_GITHUB_REPO_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

// 版本号全局变量类型定义
interface Window {
  __VERSION__?: {
    version: string
    gitTag: string
    githubRepoUrl: string
  }
}

