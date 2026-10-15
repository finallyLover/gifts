// ============================================================
// 应用入口
// 启动前先检测运行环境：非 Tauri WebView 时给出明确的引导提示，
// 避免用户在纯浏览器中操作时遇到模糊的 invoke 错误。
// ============================================================
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import { isTauri } from '@/api/http'
import './styles/global.css'

/// 非 Tauri 环境全屏提示
function showNotTauriOverlay() {
  const overlay = document.createElement('div')
  overlay.style.cssText = [
    'position:fixed', 'inset:0', 'z-index:99999',
    'background:rgba(0,0,0,0.78)', 'display:flex',
    'align-items:center', 'justify-content:center',
    'font-family:"Microsoft YaHei",sans-serif'
  ].join(';')
  overlay.innerHTML = `
    <div style="max-width:560px;background:#fff;border-radius:10px;padding:28px 32px;box-shadow:0 8px 30px rgba(0,0,0,.3)">
      <h2 style="margin:0 0 12px;color:#e64340">⚠ 请在桌面客户端中运行</h2>
      <p style="line-height:1.8;color:#303133;margin:8px 0">
        当前页面是在<strong>普通浏览器</strong>中打开的，缺少 Tauri 运行时桥接，
        无法访问本地 SQLite 数据库与图片文件。
      </p>
      <p style="line-height:1.8;color:#606266;margin:8px 0">
        请在项目根目录使用以下命令启动：
      </p>
      <pre style="background:#f4f4f5;padding:10px 14px;border-radius:6px;color:#c7254e;overflow-x:auto">npm run tauri dev</pre>
      <p style="line-height:1.8;color:#606266;margin:12px 0 0">
        如需打包发布，请执行 <code style="background:#f4f4f5;padding:2px 6px;border-radius:4px">npm run tauri build</code>。
      </p>
    </div>`
  document.body.appendChild(overlay)
}

// 非 Tauri 环境：挂载 UI 供预览，但叠加不可关闭的提示遮罩
if (!isTauri) {
  showNotTauriOverlay()
}

const app = createApp(App)

// 全局错误处理：把未捕获的 invoke 错误以消息形式提示（而非控制台 TypeError）
app.config.errorHandler = (err: unknown) => {
  // eslint-disable-next-line no-console
  console.error('[App Error]', err)
  const msg = err instanceof Error ? err.message : String(err)
  // 仅在 Tauri 环境外提示；避免在已存在的遮罩上重复弹框
  if (!isTauri) return
  // 此处不直接弹 ElMessage（依赖 Element Plus 上下文），交由各调用方 try/catch 处理
  void msg
}

// 全局注册 Element Plus 图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component as never)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
