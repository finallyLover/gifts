// ============================================================
// 模块5：统计报表命令
//   1. 单场礼簿统计：笔数/金额、关系分组、金额档位
//   2. 全局人情统计：收/支总额、净差额、待回礼清单
//   3. 人情对照表：每人 收/支/差额
// ============================================================
use rusqlite::{params, params_from_iter, Row};
use tauri::State;

use crate::db::DbState;
use crate::error::AppResult;
use crate::models::{
    ArchiveStat, GlobalStat, GroupStat, PendingPerson, PersonSummary,
};

/// 单场礼簿统计
#[tauri::command]
pub fn archive_stat(state: State<DbState>, archive_id: String) -> AppResult<ArchiveStat> {
    let conn = state.0.lock().map_err(crate::db::lock_err)?;

    // 总览指标：合计含礼品折算；gift_amount 为其中礼品折算部分
    let (total_count, total_amount, gift_amount, confirmed_count, pending_count) = conn.query_row(
        r#"SELECT COUNT(*),
                  COALESCE(SUM(amount + COALESCE(gift_amount,0)), 0),
                  COALESCE(SUM(gift_amount), 0),
                  SUM(CASE WHEN status='confirmed' THEN 1 ELSE 0 END),
                  SUM(CASE WHEN status='pending' THEN 1 ELSE 0 END)
           FROM gift_records WHERE archive_id=?"#,
        params![archive_id],
        |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, f64>(1)?,
                r.get::<_, f64>(2)?,
                r.get::<_, Option<i64>>(3)?.unwrap_or(0),
                r.get::<_, Option<i64>>(4)?.unwrap_or(0),
            ))
        },
    )?;

    // 按关系分组（金额含礼品折算）
    let mut stmt_rel = conn.prepare(
        r#"SELECT COALESCE(NULLIF(relation,''),'未填写') AS label,
                  COUNT(*) AS count, SUM(amount + COALESCE(gift_amount,0)) AS amount
           FROM gift_records WHERE archive_id=?
           GROUP BY relation ORDER BY amount DESC"#,
    )?;
    let by_relation = stmt_rel
        .query_map(params![archive_id], row_to_group)?
        .collect::<rusqlite::Result<Vec<_>>>()?;

    // 金额档位分布（SQL CASE 分桶）
    let mut stmt_bucket = conn.prepare(
        r#"SELECT bucket AS label, COUNT(*) AS count, SUM(amount) AS amount FROM (
                SELECT amount,
                    CASE
                        WHEN amount < 200 THEN '200以下'
                        WHEN amount < 500 THEN '200-499'
                        WHEN amount < 1000 THEN '500-999'
                        WHEN amount < 2000 THEN '1000-1999'
                        ELSE '2000以上'
                    END AS bucket
                FROM gift_records WHERE archive_id=?
           ) GROUP BY bucket"#,
    )?;
    let mut brackets_map = std::collections::BTreeMap::from([
        ("200以下".to_string(), GroupStat { label: "200以下".to_string(), count: 0, amount: 0.0 }),
        ("200-499".to_string(), GroupStat { label: "200-499".to_string(), count: 0, amount: 0.0 }),
        ("500-999".to_string(), GroupStat { label: "500-999".to_string(), count: 0, amount: 0.0 }),
        ("1000-1999".to_string(), GroupStat { label: "1000-1999".to_string(), count: 0, amount: 0.0 }),
        ("2000以上".to_string(), GroupStat { label: "2000以上".to_string(), count: 0, amount: 0.0 }),
    ]);
    for g in stmt_bucket
        .query_map(params![archive_id], row_to_group)?
        .collect::<rusqlite::Result<Vec<_>>>()?
    {
        brackets_map.insert(g.label.clone(), g);
    }

    Ok(ArchiveStat {
        total_count,
        total_amount: (total_amount * 100.0).round() / 100.0,
        gift_amount: (gift_amount * 100.0).round() / 100.0,
        confirmed_count,
        pending_count,
        by_relation,
        amount_brackets: brackets_map.into_values().collect(),
    })
}

fn row_to_group(row: &Row) -> rusqlite::Result<GroupStat> {
    Ok(GroupStat {
        label: row.get("label")?,
        count: row.get("count")?,
        amount: {
            let v: f64 = row.get("amount")?;
            (v * 100.0).round() / 100.0
        },
    })
}

/// 全局人情统计
#[tauri::command]
pub fn global_stat(state: State<DbState>) -> AppResult<GlobalStat> {
    let conn = state.0.lock().map_err(crate::db::lock_err)?;

    // 收礼合计（含礼品折算）
    let (in_count, total_in, gift_in): (i64, f64, f64) = conn.query_row(
        "SELECT COUNT(*), COALESCE(SUM(amount + COALESCE(gift_amount,0)),0), COALESCE(SUM(gift_amount),0) FROM gift_records",
        [],
        |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
    )?;
    // 回礼合计（含礼品折算）
    let (out_count, total_out, gift_out): (i64, f64, f64) = conn.query_row(
        "SELECT COUNT(*), COALESCE(SUM(amount + COALESCE(gift_amount,0)),0), COALESCE(SUM(gift_amount),0) FROM return_gifts",
        [],
        |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
    )?;

    // 待回礼人员：有收礼记录且状态为 pending（总额含礼品折算）
    let mut stmt = conn.prepare(
        r#"SELECT p.id, p.name,
                  (SELECT COALESCE(SUM(amount + COALESCE(gift_amount,0)),0) FROM gift_records gr WHERE gr.person_id=p.id) AS total_in,
                  (SELECT MAX(created_at) FROM gift_records gr WHERE gr.person_id=p.id) AS last_date
           FROM persons p
           WHERE p.return_status='pending'
             AND EXISTS (SELECT 1 FROM gift_records gr WHERE gr.person_id=p.id)
           ORDER BY total_in DESC"#,
    )?;
    let pending_persons = stmt
        .query_map([], |r| {
            Ok(PendingPerson {
                id: r.get(0)?,
                name: r.get(1)?,
                total_in: r.get(2)?,
                last_date: r.get(3)?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;

    Ok(GlobalStat {
        total_in: (total_in * 100.0).round() / 100.0,
        total_out: (total_out * 100.0).round() / 100.0,
        gift_in: (gift_in * 100.0).round() / 100.0,
        gift_out: (gift_out * 100.0).round() / 100.0,
        net_diff: ((total_in - total_out) * 100.0).round() / 100.0,
        in_count,
        out_count,
        pending_persons,
    })
}

/// 人情对照表（每个人 对方赠我/我赠对方/差额）
#[tauri::command]
pub fn comparison_report(
    state: State<DbState>,
    keyword: Option<String>,
) -> AppResult<Vec<PersonSummary>> {
    let conn = state.0.lock().map_err(crate::db::lock_err)?;
    let sql = r#"
        SELECT p.*,
            COALESCE((SELECT SUM(amount + COALESCE(gift_amount,0)) FROM gift_records gr WHERE gr.person_id=p.id), 0) AS total_in,
            COALESCE((SELECT SUM(amount + COALESCE(gift_amount,0)) FROM return_gifts rg WHERE rg.person_id=p.id), 0) AS total_out,
            COALESCE((SELECT SUM(gift_amount) FROM gift_records gr WHERE gr.person_id=p.id), 0) AS gift_in,
            COALESCE((SELECT SUM(gift_amount) FROM return_gifts rg WHERE rg.person_id=p.id), 0) AS gift_out,
            (SELECT COUNT(*) FROM gift_records gr WHERE gr.person_id=p.id) AS in_count,
            (SELECT COUNT(*) FROM return_gifts rg WHERE rg.person_id=p.id) AS out_count
        FROM persons p
        WHERE 1=1
        "#;
    let mut full = sql.to_string();
    let mut args: Vec<Box<dyn rusqlite::ToSql>> = Vec::new();
    if let Some(kw) = keyword.filter(|s| !s.trim().is_empty()) {
        full.push_str(" AND p.name LIKE ?");
        args.push(Box::new(format!("%{}%", kw)));
    }
    full.push_str(" ORDER BY total_in - total_out DESC, total_in DESC");

    let mut stmt = conn.prepare(&full)?;
    let list = stmt
        .query_map(params_from_iter(args.iter()), |row| {
            let total_in: f64 = row.get("total_in")?;
            let total_out: f64 = row.get("total_out")?;
            Ok(PersonSummary {
                id: row.get("id")?,
                name: row.get("name")?,
                pinyin: row.get("pinyin")?,
                remark: row.get("remark")?,
                return_status: row.get("return_status")?,
                total_in,
                total_out,
                gift_in: row.get("gift_in")?,
                gift_out: row.get("gift_out")?,
                diff: ((total_in - total_out) * 100.0).round() / 100.0,
                in_count: row.get("in_count")?,
                out_count: row.get("out_count")?,
                created_at: row.get("created_at")?,
                updated_at: row.get("updated_at")?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(list)
}
