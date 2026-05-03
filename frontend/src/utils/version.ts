/**
 * 版本号配置
 * 在构建时通过环境变量注入
 */
export interface VersionInfo {
  version: string
  gitTag: string
  githubRepoUrl: string
}

const DEFAULT_REPO_URL = ''

/**
 * 获取版本信息
 * 从 window.__VERSION__ 或环境变量中读取
 */
export const getVersionInfo = (): VersionInfo => {
  // 优先从 window.__VERSION__ 读取（构建时注入）
  const windowVersion = window.__VERSION__
  if (windowVersion) {
    return {
      version: windowVersion.version || 'dev',
      gitTag: windowVersion.gitTag || '',
      githubRepoUrl: windowVersion.githubRepoUrl || DEFAULT_REPO_URL
    }
  }
  
  // 从环境变量读取（开发环境）
  return {
    version: import.meta.env.VITE_APP_VERSION || 'dev',
    gitTag: import.meta.env.VITE_APP_GIT_TAG || '',
    githubRepoUrl: import.meta.env.VITE_APP_GITHUB_REPO_URL || DEFAULT_REPO_URL
  }
}

/**
 * 获取版本号显示文本
 */
export const getVersionText = (): string => {
  const info = getVersionInfo()
  return info.version
}

/**
 * 获取 GitHub tag 页面 URL
 */
export const getGitHubTagUrl = (): string => {
  const info = getVersionInfo()
  if (!info.githubRepoUrl) {
    return ''
  }
  if (info.gitTag) {
    return `${info.githubRepoUrl}/releases/tag/${info.gitTag}`
  }
  return info.githubRepoUrl
}

