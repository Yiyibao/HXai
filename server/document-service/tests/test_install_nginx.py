import subprocess
from pathlib import Path

import pytest
from deploy import install_nginx


def fixture_files(tmp_path, monkeypatch, mode, existing=True):
    def path(location):
        return tmp_path / location.lstrip('/')
    config = path('/etc/nginx/sites-enabled/hxnf.top')
    snippet = path('/etc/nginx/snippets/mathdoc-location.conf')
    limits = path('/etc/nginx/conf.d/mathdoc-limits.conf')
    source = path('/tmp/mathdoc-nginx.conf')
    source_limits = path('/tmp/mathdoc-limits.conf')
    for file in (config, snippet, limits, source, source_limits):
        file.parent.mkdir(parents=True, exist_ok=True)
    anchor = 'mathstudy-location.conf' if mode == 'origin' else 'spring-willow-location.conf'
    config.write_bytes(f'server {{\r\n    include /etc/nginx/snippets/{anchor};\r\n}}\r\n'.encode())
    if existing:
        snippet.write_bytes(b'original snippet\r\n')
        limits.write_bytes(b'original limits\r\n')
    source.write_bytes(b'new location configuration\n')
    source_limits.write_bytes(b'new rate limit configuration\n')
    originals = {file: file.read_bytes() if file.exists() else None for file in (config, snippet, limits)}
    monkeypatch.setattr(install_nginx, 'Path', path)
    return config, snippet, limits, originals


def assert_originals(originals):
    for file, content in originals.items():
        if content is None:
            assert not file.exists()
        else:
            assert file.read_bytes() == content


@pytest.mark.parametrize('mode', ['origin', 'entry'])
@pytest.mark.parametrize('existing', [False, True])
@pytest.mark.parametrize('failure', ['nginx', 'systemctl'])
def test_validation_or_reload_failure_restores_bytes_and_existence(tmp_path, monkeypatch, mode, existing, failure):
    _, _, _, originals = fixture_files(tmp_path, monkeypatch, mode, existing)
    def run(command, check):
        if command[0] == failure:
            raise subprocess.CalledProcessError(1, command)
    monkeypatch.setattr(install_nginx.subprocess, 'run', run)
    with pytest.raises(subprocess.CalledProcessError):
        install_nginx.install(mode)
    assert_originals(originals)
    backups = tmp_path / 'etc/mathdoc-nginx-backups'
    for file, content in originals.items():
        if content is not None and (mode == 'entry' or file.name != 'mathdoc-limits.conf'):
            assert next(backups.glob('*/' + file.name)).read_bytes() == content


def test_partial_second_write_restores_already_changed_snippet(tmp_path, monkeypatch):
    _, _, limits, originals = fixture_files(tmp_path, monkeypatch, 'entry')
    write_bytes = Path.write_bytes
    failed = False
    def fail_once(file, content):
        nonlocal failed
        if file == limits and not failed:
            failed = True
            write_bytes(file, b'partial write')
            raise OSError('controlled write failure')
        return write_bytes(file, content)
    monkeypatch.setattr(Path, 'write_bytes', fail_once)
    with pytest.raises(OSError, match='controlled write failure'):
        install_nginx.install('entry')
    assert_originals(originals)


def test_invalid_existing_anchor_never_changes_any_live_file(tmp_path, monkeypatch):
    config, _, _, originals = fixture_files(tmp_path, monkeypatch, 'entry')
    config.write_bytes(b'server {\n}\n')
    originals[config] = config.read_bytes()
    with pytest.raises(ValueError, match='exactly one'):
        install_nginx.install('entry')
    assert_originals(originals)


@pytest.mark.parametrize('mode', ['origin', 'entry'])
def test_success_preserves_other_routes_and_origin_limits(tmp_path, monkeypatch, mode):
    config, snippet, limits, originals = fixture_files(tmp_path, monkeypatch, mode)
    monkeypatch.setattr(install_nginx.subprocess, 'run', lambda *args, **kwargs: None)
    install_nginx.install(mode)
    assert snippet.read_bytes() == b'new location configuration\n'
    expected = originals[config].replace(b';\r\n', b';\n    include /etc/nginx/snippets/mathdoc-location.conf;\r\n', 1)
    assert config.read_bytes() == expected
    assert limits.read_bytes() == (b'new rate limit configuration\n' if mode == 'entry' else originals[limits])
