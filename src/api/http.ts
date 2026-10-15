// ============================================================
// 底层 Tauri invoke 封装
//
// 关键：增加运行环境守卫。当应用在纯浏览器（非 Tauri WebView）中打开时，
// `@tauri-apps/api/core` 导出的 `invoke` 会因缺少 IPC 桥接而变成 undefined，
// 直接调用会抛出 `Cannot read properties of undefined (reading 'invoke')`。
// 这里统一探测并给出明确提示，便于定位问题。
// ============================================================
import * as tauriCore from '@tauri-apps/api/core'

/** 是否运行在 Tauri WebView 中（同时兼容 v1 `__TAURI__` 与 v2 `__TAURI_INTERNALS__`） */
export const isTauri: boolean =
  typeof window !== 'undefined' &&
  ((window as unknown as { __TAURI__?: unknown }).__TAURI__ !== undefined ||
    (window as unknown as { __TAURI_INTERNALS__?: unknown }).__TAURI_INTERNALS__ !== undefined)

/** 实际可用的 invoke 函数（非 Tauri 环境为 undefined） */
const rawInvoke = (tauriCore as { invoke?: <T>(cmd: string, args?: Record<string, unknown>) => Promise<T> }).invoke

/** 非 Tauri 环境的统一错误信息 */
const NOT_TAURI_MSG =
  '当前运行环境不是 Tauri WebView，无法访问本地数据库与文件系统。\n' +
  '请使用 `npm run tauri dev` 启动开发，或使用打包后的桌面客户端运行本软件。'

/**
 * 安全的 invoke 调用：
 *   - 在 Tauri 环境：正常转发到 Rust 命令
 *   - 非 Tauri 环境：抛出明确错误（便于 UI 层捕获提示）
 */
export function call<T>(command: string, args?: Record<string, unknown>): Promise<T> {
  if (!isTauri || typeof rawInvoke !== 'function') {
    return Promise.reject(new Error(NOT_TAURI_MSG))
  }
  return rawInvoke<T>(command, args)
}
