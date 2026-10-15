package com.example.afigts.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================
// 与电脑端 Element Plus 风格对齐的配色方案
// 主色：#409EFF（Element Plus Primary Blue）
// 背景：#f0f2f5（电脑端 layout-main 背景）
// 深侧栏：#2b2f38（电脑端侧边栏背景）
// 激活黄：#f5d76e（电脑端激活项文字色）
// ============================================================

// 电脑端主色调（Element Plus Primary）
val PrimaryBlue = Color(0xFF409EFF)
val PrimaryBlueLight = Color(0xFF66B1FF)
val PrimaryBlueDark = Color(0xFF3A8EE6)

// 电脑端成功 / 警告 / 危险色
val SuccessGreen = Color(0xFF67C23A)
val WarningOrange = Color(0xFFE6A23C)
val DangerRed = Color(0xFFF56C6C)

// 电脑端背景色
val AppBackground = Color(0xFFF0F2F5)
val HeaderBackground = Color(0xFFFFFFFF)

// 深侧栏（对应电脑端 layout-aside）
val SidebarDark = Color(0xFF2B2F38)
val SidebarHover = Color(0xFF343944)
val SidebarActiveBg = Color(0xFF3A3F4B)
val SidebarText = Color(0xFFC8CCD4)
val SidebarTextActive = Color(0xFFF5D76E)  // 激活项黄色

// 语义色：收礼/回礼（与电脑端 PersonList 对齐）
val InColor = Color(0xFFE64340)    // 收礼（红）
val OutColor = Color(0xFF2B7D2B)   // 回礼（绿）
val PendingColor = Color(0xFFE6A23C) // 待回礼（橙）
