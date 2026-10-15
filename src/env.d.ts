/// <reference types="vite/client" />

// html2pdf.js 无官方类型声明，补充最小模块声明
declare module 'html2pdf.js' {
  const html2pdf: any
  export default html2pdf
}
