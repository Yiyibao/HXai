"""Bounded OOXML preflight before a native Office engine sees untrusted input."""
import hashlib
import xml.etree.ElementTree as ET
from zipfile import ZipFile

MAIN = {"docx": "word/document.xml", "xlsx": "xl/workbook.xml", "pptx": "ppt/presentation.xml"}


def inspect_package(source, extension, digest):
    if extension not in MAIN or source.stat().st_size > 30 * 1024 * 1024:
        raise ValueError("invalid_office_input")
    if hashlib.sha256(source.read_bytes()).hexdigest() != digest:
        raise ValueError("source_digest_mismatch")
    fonts = set()
    with ZipFile(source) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        if not 1 <= len(entries) <= 2000 or len(names) != len(set(names)) or MAIN[extension] not in names or "[Content_Types].xml" not in names:
            raise ValueError("invalid_office_package")
        expanded = 0
        for entry in entries:
            name = entry.filename
            if name.startswith('/') or '\\' in name or ':' in name or '..' in name.split('/') or entry.flag_bits & 1:
                raise ValueError("encrypted_or_unsafe_office")
            if (name.startswith('_xmlsignatures/') or name.lower().endswith('vbaproject.bin') or
                    '/activex/' in name.lower() or '/embeddings/' in name.lower() or name.startswith('xl/externalLinks/')):
                raise ValueError("active_or_external_office_content")
            expanded += entry.file_size
            if entry.file_size > 16 * 1024 * 1024 or expanded > 64 * 1024 * 1024:
                raise ValueError("office_expansion_limit")
            if name.endswith(('.xml', '.rels')):
                if entry.file_size > 4 * 1024 * 1024:
                    raise ValueError("office_xml_limit")
                data = archive.read(entry)
                if b'<!DOCTYPE' in data.upper() or b'<!ENTITY' in data.upper() or b'\x00' in data:
                    raise ValueError("unsafe_office_xml")
                root = ET.fromstring(data)
                for node in root.iter():
                    local = node.tag.rsplit('}', 1)[-1]
                    if local == 'Relationship' and node.get('TargetMode') == 'External' and not node.get('Type', '').endswith('/hyperlink'):
                        raise ValueError("external_office_resource")
                    if local == 'rFonts':
                        for key, value in node.attrib.items():
                            if key.rsplit('}', 1)[-1] in {'ascii', 'hAnsi', 'eastAsia', 'cs'}:
                                fonts.add(value)
                    if local in {'latin', 'ea', 'cs'} and node.get('typeface'):
                        fonts.add(node.get('typeface'))
                    if local == 'name' and name.startswith('xl/') and node.get('val'):
                        fonts.add(node.get('val'))
    return sorted(font for font in fonts if not font.startswith('+') and len(font) <= 128)[:100]
