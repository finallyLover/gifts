// ============================================================
// 通用格式化工具
// ============================================================
package com.example.afigts.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 当前本地时间："yyyy-MM-dd HH:mm:ss"（与桌面端 chrono 输出格式一致） */
fun nowStr(): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

/** 金额格式化：千分位，最多两位小数 */
fun fmtMoney(v: Double): String {
    val df = DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.CHINA))
    return df.format(v)
}

/** 金额格式化（带 ¥ 前缀） */
fun fmtYuan(v: Double): String = "¥" + fmtMoney(v)

/** 保留两位小数（四舍五入） */
fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

/** 安全解析金额输入，非法或负值返回 0 */
fun parseAmount(text: String): Double {
    val v = text.trim().toDoubleOrNull() ?: 0.0
    return if (v < 0.0) 0.0 else v
}
