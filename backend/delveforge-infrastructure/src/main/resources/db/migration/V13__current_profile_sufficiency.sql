-- 每份画像只保存一个服务端通过的最低充分性版本，不保存 Prompt、问题、原始响应或历史评估。
-- Profile revision 已绑定六区内容和 Evidence 的不可覆盖快照；内容改变后旧记录由 Adapter 删除。
CREATE TABLE user_profile_sufficiency (
    profile_id TEXT NOT NULL PRIMARY KEY,
    revision INTEGER NOT NULL
);
