// ============================================================
// PDF 导出 / 打印（html2pdf.js）
//   exportElementPdf ：把页面中的任意元素导出
//   exportLedgerPdf  ：复刻传统礼簿表格样式打印清单
// ============================================================
import html2pdf from 'html2pdf.js'
import type { GiftRecord } from '@/types'

const DEFAULT_OPTS = {
  margin: [10, 10, 12, 10],
  image: { type: 'jpeg', quality: 0.98 },
  html2canvas: { scale: 2, useCORS: true, backgroundColor: '#ffffff' },
  jsPDF: { unit: 'mm', format: 'a4', orientation: 'portrait' }
}

/// 导出指定 DOM 元素为 PDF
export function exportElementPdf(element: HTMLElement, filename: string) {
  html2pdf()
    .set({ ...DEFAULT_OPTS, filename })
    .from(element)
    .save()
}

function escapeHtml(s: unknown): string {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

/**
 * 复刻传统礼簿样式：
 * 居中标题 + 双线边框表格（序号/姓名/礼金/礼品/备注）
 */
export function exportLedgerPdf(
  title: string,
  rows: GiftRecord[],
  subtitle = ''
) {
  const body = rows
    .map(
      (r, i) => `
      <tr>
        <td class="c-center">${i + 1}</td>
        <td>${escapeHtml(r.guest_name)}</td>
        <td class="c-right">${r.amount ? r.amount : ''}</td>
        <td class="c-right">${r.gift_amount ? r.gift_amount : ''}</td>
        <td>${escapeHtml(r.gift)}</td>
        <td>${escapeHtml(r.remark)}</td>
      </tr>`
    )
    .join('')

  // 合计行（礼金 / 礼品金额 / 总计）
  const sumAmount = rows.reduce((s, r) => s + (Number(r.amount) || 0), 0)
  const sumGift = rows.reduce((s, r) => s + (Number(r.gift_amount) || 0), 0)
  const totalRow = `
      <tr class="c-bold">
        <td class="c-center" colspan="2">合计</td>
        <td class="c-right">${sumAmount || ''}</td>
        <td class="c-right">${sumGift || ''}</td>
        <td colspan="2">总计：${(sumAmount + sumGift) || ''}</td>
      </tr>`

  const html = `
  <div class="ledger-pdf">
    <h1 class="ledger-title">${escapeHtml(title)}</h1>
    ${subtitle ? `<div class="ledger-sub">${escapeHtml(subtitle)}</div>` : ''}
    <table class="ledger-table">
      <thead>
        <tr>
          <th style="width:7%">序号</th>
          <th style="width:19%">姓名</th>
          <th style="width:13%">礼金(元)</th>
          <th style="width:13%">礼品金额(元)</th>
          <th style="width:22%">礼品</th>
          <th style="width:26%">备注</th>
        </tr>
      </thead>
      <tbody>${body}${totalRow}</tbody>
    </table>
  </div>`

  // 临时挂载渲染容器
  const container = document.createElement('div')
  container.style.position = 'fixed'
  container.style.left = '-10000px'
  container.style.top = '0'
  container.innerHTML = html
  injectLedgerStyle(container)
  document.body.appendChild(container)

  html2pdf()
    .set({ ...DEFAULT_OPTS, filename: `${title}.pdf` })
    .from(container.querySelector('.ledger-pdf') as HTMLElement)
    .save()
    .then(() => document.body.removeChild(container))
    .catch(() => document.body.removeChild(container))
}

/// 注入传统礼簿表格样式（双线外框、实线内框）
function injectLedgerStyle(container: HTMLElement) {
  const style = document.createElement('style')
  style.innerHTML = `
    .ledger-pdf { width: 100%; color: #000; font-family: SimSun, serif; }
    .ledger-title { text-align: center; font-size: 26px; letter-spacing: 8px; margin: 6px 0 10px; }
    .ledger-sub { text-align: center; font-size: 13px; margin-bottom: 10px; }
    .ledger-table { width: 100%; border-collapse: collapse;
      border-top: 3px double #000; border-bottom: 3px double #000; }
    .ledger-table th, .ledger-table td {
      border: 1px solid #000; padding: 5px 6px; font-size: 12px; height: 22px; }
    .ledger-table thead th { border-bottom: 2px solid #000; text-align: center; }
    .c-center { text-align: center; }
    .c-right { text-align: right; }
    .c-bold td { font-weight: bold; }
  `
  container.appendChild(style)
}
