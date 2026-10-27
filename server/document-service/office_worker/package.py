"""Bounded OOXML preflight before a native Office engine sees untrusted input."""
import hashlib
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

MAIN = {"docx": "word/document.xml", "xlsx": "xl/workbook.xml", "pptx": "ppt/presentation.xml"}


def parse_office_xml(data):
    """Validate and parse the same strict character stream as the Android reader."""
    if len(data) > 4 * 1024 * 1024:
        raise ValueError("office_xml_limit")
    if data.startswith((b'\xff\xfe\x00\x00', b'\x00\x00\xfe\xff')):
        raise ValueError("unsupported_xml_encoding")
    encoding = 'utf-16-le' if data.startswith(b'\xff\xfe') else 'utf-16-be' if data.startswith(b'\xfe\xff') else 'utf-8'
    try:
        text = data.decode(encoding, errors='strict').removeprefix('\ufeff')
    except UnicodeError:
        raise ValueError("unsupported_xml_encoding") from None
    if '\x00' in text:
        raise ValueError("unsupported_xml_encoding")
    if '<!DOCTYPE' in text.upper() or '<!ENTITY' in text.upper():
        raise ValueError("unsafe_office_xml")
    declaration = re.match(r"""^\s*<\?xml\s+[^?]*encoding\s*=\s*['"]([^'"]+)['"]""", text, re.I)
    allowed = {'UTF-8'} if encoding == 'utf-8' else {'UTF-16', encoding.upper()}
    if declaration and declaration[1].upper() not in allowed:
        raise ValueError("unsupported_or_mismatched_xml_encoding")
    def character(match):
        value = match[1]
        code = int(value[1:], 16) if value.startswith('x') else int(value)
        if not 0 < code <= 0x10ffff or 0xd800 <= code <= 0xdfff:
            raise ValueError("invalid_xml_character_reference")
        return chr(code)
    projection = re.sub(r'&#(x[0-9a-fA-F]+|[0-9]+);', character, text)
    if 'http://www.w3.org/2001/xinclude' in projection.casefold():
        raise ValueError("xinclude_not_supported")
    try:
        return ET.fromstring(text)
    except ET.ParseError:
        raise ValueError("invalid_office_xml") from None


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
                root = parse_office_xml(archive.read(entry))
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
