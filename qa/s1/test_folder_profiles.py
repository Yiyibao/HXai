"""Exercise production folder SQL, including ownership-preserving migration."""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DAO = ROOT / 'client/app/src/main/java/me/rerere/rikkahub/data/db/dao'

def queries(file):
    return dict((name, sql) for sql, name in re.findall(
        r'@Query\("([^"\n]+)"\)\s+(?:suspend )?fun (\w+)',
        (DAO / file).read_text(encoding='utf-8')))

class FolderProfilesTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.executescript('''
            CREATE TABLE conversation_folder(id TEXT PRIMARY KEY, assistant_id TEXT,
                name TEXT, sort_index INTEGER, create_at INTEGER);
            INSERT INTO conversation_folder VALUES ('legacy','shared','legacy',0,0);
            ALTER TABLE conversation_folder ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'default-learner';
            INSERT INTO conversation_folder VALUES ('fa','shared','a-folder',0,1,'a'), ('fb','shared','b-folder',0,2,'b');
            CREATE TABLE ConversationEntity(id TEXT, folder_id TEXT, profile_id TEXT);
            INSERT INTO ConversationEntity VALUES ('ca','fa','a'), ('cb','fa','b');
        ''')
        self.sql = queries('FolderDAO.kt')

    def tearDown(self):
        self.db.close()

    def test_migration_and_list_scope(self):
        self.assertEqual(self.db.execute("SELECT profile_id FROM conversation_folder WHERE id='legacy'").fetchone()[0], 'default-learner')
        self.assertEqual([row[0] for row in self.db.execute(self.sql['getFoldersOfAssistant'],
            dict(assistantId='shared', profileId='a'))], ['fa'])

    def test_other_profile_cannot_read_rename_or_delete(self):
        params = dict(id='fb', profileId='a', name='changed')
        self.assertIsNone(self.db.execute(self.sql['getFolderById'], params).fetchone())
        for method in ['rename', 'deleteById']:
            self.assertEqual(self.db.execute(self.sql[method], params).rowcount, 0)
        params['profileId'] = 'b'
        self.assertEqual(self.db.execute(self.sql['rename'], params).rowcount, 1)
        self.assertEqual(self.db.execute(self.sql['deleteById'], params).rowcount, 1)

    def test_deleting_folder_clears_only_owned_conversations(self):
        self.db.execute(queries('ConversationDAO.kt')['clearFolderForProfile'], dict(folderId='fa', profileId='a'))
        self.assertEqual(self.db.execute('SELECT id,folder_id FROM ConversationEntity ORDER BY id').fetchall(),
            [('ca',''), ('cb','fa')])

    def test_26_to_27_preserves_both_development_column_variants(self):
        source = (DAO.parent / 'migrations/Migration_26_27.kt').read_text(encoding='utf-8')
        rename, add_folder = re.findall(r'db.execSQL\("([^"\n]+)"\)', source)
        for column in ['profile_id', 'learner_profile_id']:
            db = sqlite3.connect(':memory:')
            db.execute(f'CREATE TABLE ConversationEntity(id TEXT, {column} TEXT NOT NULL DEFAULT \'default-learner\')')
            db.execute(f"INSERT INTO ConversationEntity VALUES ('original','learner-b')")
            db.execute('CREATE TABLE conversation_folder(id TEXT)')
            db.execute("INSERT INTO conversation_folder VALUES ('old-folder')")
            if column == 'learner_profile_id':
                db.execute(rename)
            db.execute(add_folder)
            self.assertEqual(db.execute('SELECT id,profile_id FROM ConversationEntity').fetchall(), [('original','learner-b')])
            self.assertEqual(db.execute('SELECT id,profile_id FROM conversation_folder').fetchall(), [('old-folder','default-learner')])
            db.close()

if __name__ == '__main__':
    unittest.main()
