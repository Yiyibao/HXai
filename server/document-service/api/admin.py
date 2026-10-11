"""Local administrator CLI. Tokens are written only to a mode-0600 file."""
import argparse
import json
import os
from pathlib import Path

from api.store import Store


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['issue', 'revoke'])
    parser.add_argument('--output', type=Path)
    parser.add_argument('--identity')
    arguments = parser.parse_args()
    store = Store(Path(os.environ.get('MATHDOC_DATA', '/var/lib/mathdoc')))
    if arguments.action == 'issue':
        if arguments.output is None:
            parser.error('issue requires --output; tokens are never printed')
        owner, token = store.issue()
        descriptor = os.open(arguments.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'w') as output:
            json.dump({'identity': owner, 'baseUrl': 'https://hxnf.top/documents', 'token': token}, output)
        print('credential_written')
    else:
        if not arguments.identity:
            parser.error('revoke requires --identity')
        with store.db() as db:
            db.execute('UPDATE identities SET active=0 WHERE id=?', (arguments.identity,))
        print('identity_revoked')


if __name__ == '__main__':
    main()
