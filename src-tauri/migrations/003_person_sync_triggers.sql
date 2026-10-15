-- ============================================================
-- 迁移 003：人员档案自动同步触发器
-- 背景：收礼记录的 guest_name 与回礼记录的 person_name 变更时，
--      必须同步更新 person_id 指向正确的人员档案，否则会出现
--      "改名后旧人员档案残留" 的孤儿问题。
-- 方案：在 DB 层用触发器强制保证 person_id 始终跟随姓名字段，
--      不依赖应用层代码，旧版二进制也能正确工作。
-- ============================================================

-- ---------- 收礼记录：guest_name 变更时自动同步 person_id ----------
DROP TRIGGER IF EXISTS tr_gift_records_person_insert;
CREATE TRIGGER tr_gift_records_person_insert
AFTER INSERT ON gift_records
WHEN NEW.guest_name IS NOT NULL AND trim(NEW.guest_name) != ''
BEGIN
  INSERT OR IGNORE INTO persons (id, name, return_status, created_at, updated_at)
    VALUES (lower(hex(randomblob(16))), trim(NEW.guest_name), 'pending', datetime('now'), datetime('now'));
  UPDATE gift_records SET person_id = (SELECT id FROM persons WHERE name = trim(NEW.guest_name)) WHERE id = NEW.id;
END;

DROP TRIGGER IF EXISTS tr_gift_records_person_update;
CREATE TRIGGER tr_gift_records_person_update
AFTER UPDATE OF guest_name ON gift_records
WHEN NEW.guest_name IS NOT NULL AND trim(NEW.guest_name) != ''
BEGIN
  INSERT OR IGNORE INTO persons (id, name, return_status, created_at, updated_at)
    VALUES (lower(hex(randomblob(16))), trim(NEW.guest_name), 'pending', datetime('now'), datetime('now'));
  UPDATE gift_records SET person_id = (SELECT id FROM persons WHERE name = trim(NEW.guest_name)) WHERE id = NEW.id;
END;

-- ---------- 回礼记录：person_name 变更时自动同步 person_id ----------
DROP TRIGGER IF EXISTS tr_return_gifts_person_insert;
CREATE TRIGGER tr_return_gifts_person_insert
AFTER INSERT ON return_gifts
WHEN NEW.person_name IS NOT NULL AND trim(NEW.person_name) != ''
BEGIN
  INSERT OR IGNORE INTO persons (id, name, return_status, created_at, updated_at)
    VALUES (lower(hex(randomblob(16))), trim(NEW.person_name), 'pending', datetime('now'), datetime('now'));
  UPDATE return_gifts SET person_id = (SELECT id FROM persons WHERE name = trim(NEW.person_name)) WHERE id = NEW.id;
END;

DROP TRIGGER IF EXISTS tr_return_gifts_person_update;
CREATE TRIGGER tr_return_gifts_person_update
AFTER UPDATE OF person_name ON return_gifts
WHEN NEW.person_name IS NOT NULL AND trim(NEW.person_name) != ''
BEGIN
  INSERT OR IGNORE INTO persons (id, name, return_status, created_at, updated_at)
    VALUES (lower(hex(randomblob(16))), trim(NEW.person_name), 'pending', datetime('now'), datetime('now'));
  UPDATE return_gifts SET person_id = (SELECT id FROM persons WHERE name = trim(NEW.person_name)) WHERE id = NEW.id;
END;
