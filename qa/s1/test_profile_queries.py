"""Exercise production SQL on SQLite; no Android or model service needed."""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "client/app/src/main/java/me/rerere/rikkahub"


class ProfileQueriesTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("""CREATE TABLE conversationentity (
            id TEXT PRIMARY KEY, assistant_id TEXT, title TEXT, is_pinned INTEGER,
            create_at INTEGER, update_at INTEGER, folder_id TEXT)""")
        self.db.execute("INSERT INTO conversationentity VALUES ('legacy','shared','math',1,1,1,'')")
        # Same migration as Room's additive default column migration.
        self.db.execute("ALTER TABLE conversationentity ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default-learner'")
        for profile in ("a", "b"):
            for i in range(60):
                self.db.execute("INSERT INTO conversationentity VALUES (?,?,?,?,?,?,?,?)",
                                (f"{profile}-{i}", "shared", "math", 1, i, i, "folder", profile))

    def tearDown(self):
        self.db.close()

    def test_legacy_rows_stay_in_default_profile(self):
        self.assertEqual(self.db.execute("SELECT profile_id FROM conversationentity WHERE id='legacy'").fetchone()[0],
                         "default-learner")

    def test_every_history_query_scopes_before_pagination(self):
        source = (JAVA / "data/db/dao/ConversationDAO.kt").read_text(encoding="utf-8")
        queries = re.findall(r'@Query\("([^"\n]+)"\)\s+(?:suspend )?fun (\w+)', source)
        checked = 0
        for sql, name in queries:
            if ":profileId" not in sql:
                continue
            if sql.startswith("UPDATE"):
                continue
            params = dict(profileId="a", assistantId="shared", folderId="folder", searchText="math", limit=10)
            rows = self.db.execute(sql, params).fetchall()
            if name == "countAll":
                self.assertEqual(rows, [(60,)])
            elif name == "countByAssistant":
                self.assertEqual(rows, [("shared", 60)])
            else:
                self.assertTrue(all(row[0].startswith("a-") for row in rows), name)
            checked += 1
        self.assertEqual(checked, 12)

    def test_id_mutations_cannot_target_other_profile(self):
        source = (JAVA / "data/db/dao/ConversationDAO.kt").read_text(encoding="utf-8")
        queries = re.findall(r'@Query\("([^"\n]+)"\)\s+suspend fun (\w+)', source)
        checked = 0
        for sql, name in queries:
            if not sql.startswith("UPDATE") or ":profileId" not in sql:
                continue
            if name == "clearFolderForProfile":
                continue
            params = dict(id="b-0", profileId="a", isPinned=0, assistantId="other", folderId="other")
            self.assertEqual(self.db.execute(sql, params).rowcount, 0, name)
            params["profileId"] = "b"
            self.assertEqual(self.db.execute(sql, params).rowcount, 1, name)
            checked += 1
        self.assertEqual(checked, 3)

    def test_full_text_all_assistants_stale_index_and_result_limit(self):
        # Execute production FTS ownership filter. SQLite FTS5 replaces Android's
        # jieba/simple tokenizer and snippet functions only for this host test.
        self.db.execute("CREATE VIRTUAL TABLE message_fts USING fts5(text,node_id,message_id,conversation_id,title,update_at)")
        for profile in ("b", "a"):
            for i in range(60):
                self.db.execute("INSERT INTO message_fts VALUES (?,?,?,?,?,?)",
                                ("math", str(i), str(i), f"{profile}-{i}", "math", str(i)))
        self.db.execute("INSERT INTO message_fts VALUES ('math','x','x','deleted','stale','1000')")
        source = (JAVA / "data/db/fts/MessageFtsManager.kt").read_text(encoding="utf-8")
        ownership = re.search(r'val assistantFilter = """(.*?)"""', source, re.S).group(1)
        query = "SELECT conversation_id FROM message_fts WHERE text MATCH ? " + ownership + " ORDER BY rank LIMIT 50"
        rows = self.db.execute(query, ("math", "a")).fetchall()
        self.assertEqual(len(rows), 50)
        self.assertTrue(all(row[0].startswith("a-") for row in rows))
        self.assertEqual(self.db.execute(query, ("math", "unknown")).fetchall(), [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
