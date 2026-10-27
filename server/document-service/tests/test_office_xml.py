import hashlib
from zipfile import ZipFile

import pytest
from office_worker.package import inspect_package


def inspect(tmp_path, xml):
    source = tmp_path / 'source.xlsx'
    with ZipFile(source, 'w') as archive:
        archive.writestr('[Content_Types].xml', '<Types/>')
        archive.writestr('xl/workbook.xml', xml)
    return inspect_package(source, 'xlsx', hashlib.sha256(source.read_bytes()).hexdigest())


@pytest.mark.parametrize('encoding', ['utf-8', 'utf-16', 'utf-16-be'])
def test_normal_unicode_office_xml_is_accepted(tmp_path, encoding):
    declaration = 'UTF-8' if encoding == 'utf-8' else 'UTF-16'
    xml = f'<?xml version="1.0" encoding="{declaration}"?><workbook><name val="教材宋体"/></workbook>'.encode(encoding)
    if encoding == 'utf-16-be':
        xml = b'\xfe\xff' + xml
    assert inspect(tmp_path, xml) == ['教材宋体']


@pytest.mark.parametrize('encoding', ['utf-8', 'utf-16'])
def test_dtd_is_rejected_in_exact_decoded_stream(tmp_path, encoding):
    xml = '<!DOCTYPE workbook [<!ENTITY note "hidden">]><workbook>&note;</workbook>'.encode(encoding)
    with pytest.raises(ValueError, match='unsafe_office_xml'):
        inspect(tmp_path, xml)


@pytest.mark.parametrize('namespace', ['http://www.w3.org/2001/XInclude', 'http://www.w3.org/2001/X&#73;nclude'])
def test_xinclude_namespace_is_rejected_without_fetching_resource(tmp_path, namespace):
    xml = f'<workbook xmlns:external="{namespace}"><external:include href="file:///synthetic-sentinel"/></workbook>'.encode()
    with pytest.raises(ValueError, match='xinclude_not_supported'):
        inspect(tmp_path, xml)


@pytest.mark.parametrize('xml', [
    b'<?xml version="1.0" encoding="UTF-16"?><workbook/>',
    b'<workbook>\xff</workbook>',
    b'\xff\xfe\x00\x00<\x00\x00\x00',
    '<workbook/>'.encode('utf-16')[0:-1],
])
def test_malformed_or_mismatched_encoding_is_rejected(tmp_path, xml):
    with pytest.raises(ValueError):
        inspect(tmp_path, xml)
