// 主入口：仅做窗口子系统设置，逻辑统一在 lib.rs
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    gift_ledger_lib::run()
}
