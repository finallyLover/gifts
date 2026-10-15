// ============================================================
// Excel 导出（SheetJS / xlsx）
// ============================================================
import * as XLSX from 'xlsx'

export interface SheetData {
  name: string
  rows: Record<string, unknown>[]
  /** 列宽（字符数），可选 */
  widths?: number[]
}

/// 单工作表导出
export function exportArrayExcel(
  filename: string,
  sheetName: string,
  rows: Record<string, unknown>[]
) {
  exportMultiSheetExcel(filename, [{ name: sheetName, rows }])
}

/// 多工作表导出
export function exportMultiSheetExcel(filename: string, sheets: SheetData[]) {
  const wb = XLSX.utils.book_new()
  for (const sheet of sheets) {
    const ws = XLSX.utils.json_to_sheet(sheet.rows)
    // 自动列宽（按表头与内容最大长度估算，限制上下限）
    if (!sheet.widths) {
      const headers = sheet.rows.length ? Object.keys(sheet.rows[0]) : []
      const colInfo = headers.map((h) => {
        let max = h.length
        for (const r of sheet.rows.slice(0, 500)) {
          const v = r[h]
          const len = v === null || v === undefined ? 0 : String(v).length
          if (len > max) max = len
        }
        return { wch: Math.min(Math.max(max + 2, 8), 40) }
      })
      ws['!cols'] = colInfo
    } else {
      ws['!cols'] = sheet.widths.map((w) => ({ wch: w }))
    }
    XLSX.utils.book_append_sheet(wb, ws, sheet.name.slice(0, 31))
  }
  XLSX.writeFile(wb, filename)
}
