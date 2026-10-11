"""Run production favorite ownership SQL against adversarial SQLite fixtures."""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'client/app/src/main/java/me/rerere/rikkahub/data/db/dao/FavoriteDAO.kt'
QUERIES = dict((name, sql) for sql, name in re.findall(
    r'@Query\("([^"\n]+)"\)\s+(?:suspend )?fun (\w+)', SOURCE.read_text(encoding='utf-8')))


class FavoriteProfilesTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.executescript('''
            CREATE TABLE ConversationEntity(id TEXT PRIMARY KEY, profile_id TEXT);
            CREATE TABLE message_node(id TEXT PRIMARY KEY, conversation_id TEXT);
            CREATE TABLE favorites(id TEXT PRIMARY KEY, ref_key TEXT, created_at INTEGER);
            INSERT INTO ConversationEntity VALUES ('ca','a'), ('cb','b');
            INSERT INTO message_node VALUES ('na','ca'), ('nb','cb');
            INSERT INTO favorites VALUES
                ('fa','node:ca:na',1), ('fb','node:cb:nb',2),
                ('missing-node','node:ca:deleted',3),
                ('wrong-pair','node:ca:nb',4),
                ('missing-chat','node:deleted:na',5);
        ''')

    def tearDown(self):
        self.db.close()

    def test_list_hides_other_owner_and_orphans(self):
        for owner, expected in [('a', 'fa'), ('b', 'fb')]:
            rows = self.db.execute(QUERIES['listForProfile'], dict(profileId=owner)).fetchall()
            self.assertEqual([row[0] for row in rows], [expected])
        self.assertEqual(self.db.execute(QUERIES['listForProfile'], dict(profileId='unknown')).fetchall(), [])

    def test_access_requires_exact_node_and_conversation_pair(self):
        for key, expected in [('node:ca:na', 1), ('node:cb:nb', 0),
                              ('node:ca:nb', 0), ('node:ca:deleted', 0)]:
            self.assertEqual(self.db.execute(QUERIES['canAccessNode'],
                dict(refKey=key, profileId='a')).fetchone()[0], expected)

    def test_delete_checks_owner_in_same_statement(self):
        for method, target in [('deleteRefForProfile', 'refKey'), ('deleteIdForProfile', 'id')]:
            value = 'node:cb:nb' if target == 'refKey' else 'fb'
            self.assertEqual(self.db.execute(QUERIES[method],
                {target: value, 'profileId': 'a'}).rowcount, 0)
        self.assertEqual(self.db.execute(QUERIES['deleteRefForProfile'],
            dict(refKey='node:ca:na', profileId='a')).rowcount, 1)

    def test_read_by_reference_cannot_bypass_list_filter(self):
        self.assertIsNone(self.db.execute(QUERIES['getRefForProfile'],
            dict(refKey='node:cb:nb', profileId='a')).fetchone())
        self.assertEqual(self.db.execute(QUERIES['getRefForProfile'],
            dict(refKey='node:ca:na', profileId='a')).fetchone()[0], 'fa')

    def test_deleted_conversation_cannot_reappear_as_favorite(self):
        self.db.execute("DELETE FROM ConversationEntity WHERE id='ca'")
        self.assertEqual(self.db.execute(QUERIES['listForProfile'], dict(profileId='a')).fetchall(), [])
        self.assertEqual(self.db.execute(QUERIES['canAccessNode'],
            dict(refKey='node:ca:na', profileId='a')).fetchone()[0], 0)


if __name__ == '__main__':
    unittest.main()
