"""Read back Flutter resources and AOT code. Does not prove application startup."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = 'assets/flutter_assets/'
DIGEST = lambda value: hashlib.sha256(value).hexdigest()


def elf_load_fingerprint(data):
    """Compare loaded ELF sections; Android legitimately strips debug symbols.

    Section file offsets/indexes and non-allocated debug tables can change during
    strip. Machine/entry/flags, names, addresses, sizes and ALL SHF_ALLOC content
    must remain identical. Bounds checks reject truncated or ambiguous files.
    """
    if len(data) < 64 or data[:4] != b'\x7fELF' or data[4] not in (1, 2) or data[5] not in (1, 2):
        raise ValueError('Invalid ELF header')
    endian = '<' if data[5] == 1 else '>'
    fmt = endian + ('HHIIIIIHHHHHH' if data[4] == 1 else 'HHIQQQIHHHHHH')
    header = struct.unpack_from(fmt, data, 16)
    kind, machine, version, entry, phoff, shoff, flags, _, phsize, phcount, shsize, count, names_index = header
    program_fmt = endian + ('IIIIIIII' if data[4] == 1 else 'IIQQQQQQ')
    if phsize != struct.calcsize(program_fmt) or not 0 < phcount < 65535 or phoff + phcount * phsize > len(data):
        raise ValueError('Invalid ELF program table')
    programs = [struct.unpack_from(program_fmt, data, phoff + i * phsize) for i in range(phcount)]
    section_fmt = endian + ('IIIIIIIIII' if data[4] == 1 else 'IIQQQQIIQQ')
    expected_size = struct.calcsize(section_fmt)
    if version != 1 or shsize != expected_size or not 0 < count < 65535 or names_index >= count or shoff + count * shsize > len(data):
        raise ValueError('Invalid ELF section table')
    sections = [struct.unpack_from(section_fmt, data, shoff + i * shsize) for i in range(count)]
    def contents(offset, size):
        if offset > len(data) or size > len(data) - offset:
            raise ValueError('ELF section outside file')
        return data[offset:offset + size]
    strings = contents(sections[names_index][4], sections[names_index][5])
    allocated = {}
    for name_offset, section_type, section_flags, address, offset, size, _, _, align, entry_size in sections:
        if not section_flags & 2:  # SHF_ALLOC
            continue
        end = strings.find(b'\0', name_offset)
        if name_offset >= len(strings) or end < 0:
            raise ValueError('Invalid ELF section name')
        name = strings[name_offset:end].decode('ascii')
        if name in allocated:
            raise ValueError('Duplicate allocated ELF section')
        value = None if section_type == 8 else DIGEST(contents(offset, size))  # SHT_NOBITS
        allocated[name] = [section_type, section_flags, address, size, align, entry_size, value]
    if '.text' not in allocated:
        raise ValueError('ELF has no code section')
    return DIGEST(json.dumps([list(data[:9]), kind, machine, entry, flags, programs, allocated], sort_keys=True).encode())


def verify(apk, aar, mode, flutter_sdk=None):
    failures = []
    flutter_sdk = flutter_sdk or ROOT/'.local/flutter'
    with ZipFile(apk) as host, ZipFile(aar) as module:
        host_names, module_names = set(host.namelist()), set(module.namelist())
        if len(host_names) != len(host.namelist()) or len(module_names) != len(module.namelist()):
            failures.append('Duplicate ZIP entries')
        assets = sorted(name for name in module_names if name.startswith(ASSETS) and not name.endswith('/'))
        if not assets:
            failures.append('No Flutter assets in module')
        mismatches = [name for name in assets if name not in host_names or DIGEST(host.read(name)) != DIGEST(module.read(name))]
        failures.extend('Stale or missing asset: ' + name for name in mismatches)
        unexpected = sorted(name for name in host_names if name.startswith(ASSETS) and not name.endswith('/') and name not in module_names)
        failures.extend('Unexpected Flutter asset: ' + name for name in unexpected)
        # All native ABIs must have Flutter, including ABIs missing the whole engine.
        abis = sorted({name.split('/')[1] for name in host_names if name.startswith('lib/') and name.endswith('.so')})
        if not abis:
            failures.append('No native ABIs in APK')
        native = {}
        engines = {}
        for abi in abis:
            engine = f'lib/{abi}/libflutter.so'
            if engine not in host_names or not host.read(engine).startswith(b'\x7fELF'):
                failures.append('Missing or invalid Flutter engine: ' + abi)
            else:
                platform = {'arm64-v8a': 'android-arm64', 'armeabi-v7a': 'android-arm', 'x86_64': 'android-x64', 'x86': 'android-x86'}.get(abi)
                suffix = '-release' if mode == 'release' else ''
                trusted = flutter_sdk/f'bin/cache/artifacts/engine/{platform}{suffix}/flutter.jar'
                if platform is None or not trusted.is_file():
                    failures.append('Matching SDK engine unavailable: ' + abi)
                else:
                    with ZipFile(trusted) as expected_engine:
                        actual = DIGEST(host.read(engine))
                        try:
                            if engine not in expected_engine.namelist() or elf_load_fingerprint(host.read(engine)) != elf_load_fingerprint(expected_engine.read(engine)):
                                failures.append('Flutter engine differs from selected SDK: ' + abi)
                            else:
                                engines[abi] = actual
                        except (ValueError, struct.error, UnicodeDecodeError):
                            failures.append('Malformed Flutter engine ELF: ' + abi)
            if mode == 'release':
                app, source = f'lib/{abi}/libapp.so', f'jni/{abi}/libapp.so'
                if source not in module_names or app not in host_names:
                    failures.append('Missing Release AOT code: ' + abi)
                elif not module.read(source).startswith(b'\x7fELF') or DIGEST(host.read(app)) != DIGEST(module.read(source)):
                    failures.append('Stale or invalid Release AOT code: ' + abi)
                else:
                    native[abi] = DIGEST(host.read(app))
        if mode == 'release':
            for name in ['kernel_blob.bin', 'vm_snapshot_data', 'isolate_snapshot_data']:
                if ASSETS + name in host_names or ASSETS + name in module_names:
                    failures.append('Debug snapshot in Release: ' + name)
        elif ASSETS + 'kernel_blob.bin' not in module_names:
            failures.append('Debug module has no Dart kernel')
    return {
        'mode': mode, 'status': 'FAIL' if failures else 'PASS',
        'apk': str(apk), 'apk_sha256': DIGEST(apk.read_bytes()),
        'aar': str(aar), 'aar_sha256': DIGEST(aar.read_bytes()),
        'native_abis': abis, 'aot_sha256': native,
        'flutter_sdk': str(flutter_sdk), 'engine_sha256': engines,
        'assets_checked': len(assets), 'mismatches': mismatches,
        'unexpected_assets': unexpected, 'failures': failures,
        'sources': {str(path.relative_to(ROOT)): DIGEST(path.read_bytes())
                    for path in sorted((ROOT/'client/flutter_ui/lib').rglob('*.dart'))},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--mode', choices=['debug', 'release'], default='debug')
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--aar', type=Path, help='Exact frozen module AAR from the build')
    parser.add_argument('--flutter-sdk', type=Path, default=ROOT/'.local/flutter', help='SDK used to build the AAR; binds native engine bytes')
    args = parser.parse_args()
    mode = args.mode
    filename = 'app-x86_64-release-unsigned.apk' if mode == 'release' else 'app-x86_64-debug.apk'
    apk = (args.apk or ROOT/f'client/app/build/outputs/apk/{mode}/{filename}').resolve()
    aar = (args.aar or ROOT/f'client/flutter_ui/build/host/outputs/repo/me/rerere/rikkahub/flutter_ui/flutter_{mode}/1.0/flutter_{mode}-1.0.aar').resolve()
    report = verify(apk, aar, mode, args.flutter_sdk.resolve())
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'{report["status"]}: {report["assets_checked"]} Flutter assets; {mode}; {", ".join(report["native_abis"])}; APK SHA-256 {report["apk_sha256"]}')
    if report['failures']:
        raise SystemExit('\n'.join(report['failures']))


if __name__ == '__main__':
    main()
