-- ============================================================
-- 迁移 002：新增"礼品金额"独立字段
--   语义：填了礼品金额 = 送了价值该金额的礼品（计入金额统计）；
--         未填（为0） = 送的是实物礼品（不计金额）。
-- ============================================================

-- 收礼记录：礼品折算金额
ALTER TABLE gift_records ADD COLUMN gift_amount REAL NOT NULL DEFAULT 0 CHECK(gift_amount >= 0);

-- 回礼记录：礼品折算金额
ALTER TABLE return_gifts ADD COLUMN gift_amount REAL NOT NULL DEFAULT 0 CHECK(gift_amount >= 0);
