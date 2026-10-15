// ============================================================
// 前端通用工具
// ============================================================

/// 提取错误文本
export function errText(e: unknown): string {
  if (e instanceof Error) return e.message
  return String(e)
}
