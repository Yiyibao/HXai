"""Read actual synthetic Android outputs with independent pypdf/PDFium engines."""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import json
import tarfile

from pypdf import PdfReader
import pypdfium2 as pdfium


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def raster(page):
    scale = 1024 / max(page.get_size())
    bitmap = page.render(scale=scale)
    try:
        image = bitmap.to_pil().convert('RGB')
        return image.size, hashlib.sha256(image.tobytes()).hexdigest()
    finally:
        bitmap.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--run-dir', type=Path, required=True)
    args = parser.parse_args()
    out = args.run_dir.resolve()
    out.mkdir(parents=True, exist_ok=False)
    with tarfile.open(args.archive) as archive:
        for member in archive.getmembers():
            path = PurePosixPath(member.name)
            assert not path.is_absolute() and '..' not in path.parts
            assert member.isfile() or member.isdir()
            assert member.size <= 30 * 1024 * 1024
        archive.extractall(out, filter='data')
    folder = out / 'cache/b-pdf-assembly-evidence'
    items = {label: json.loads((folder / f'{label}.meta.json').read_text(encoding='utf-8')) for label in ['first', 'second', 'output']}
    for label, item in items.items():
        file = folder / f'{label}.pdf'
        assert digest(file) == item['sha256'] and file.stat().st_size == item['size']
    assert len({item['profileId'] for item in items.values()}) == 1
    proof = json.loads(items['output']['detail'])
    assert proof['contractVersion'] == 1 and proof['scope'] == 'pdf_original_page_assembly'
    assert proof['verification'] == 'REOPENED_PAGE_GEOMETRY_TEXT_AND_RASTER_MATCH'
    assert proof['page_numbering'] == 'pdf_physical_page_1_based' and proof['raster_long_edge'] == 1024
    expected = [('first', 3), ('first', 2), ('first', 3), ('second', 1)]
    readers = {label: PdfReader(folder / f'{label}.pdf') for label in items}
    documents = {label: pdfium.PdfDocument(folder / f'{label}.pdf') for label in items}
    assert len(readers['output'].pages) == len(proof['pages']) == len(expected)
    rows = []
    try:
        for index, (label, number) in enumerate(expected):
            mapping = proof['pages'][index]
            assert mapping['source_id'] == items[label]['id'] and mapping['source_sha256'] == items[label]['sha256']
            assert mapping['source_physical_page'] == number and mapping['output_physical_page'] == index + 1
            source_page = readers[label].pages[number - 1]
            output_page = readers['output'].pages[index]
            assert source_page.mediabox == output_page.mediabox
            assert source_page.cropbox == output_page.cropbox and source_page.rotation == output_page.rotation
            assert source_page.extract_text() == output_page.extract_text()
            a = documents[label][number - 1]
            b = documents['output'][index]
            try:
                assert a.get_size() == b.get_size()
                source_raster = raster(a)
                assert source_raster == raster(b)
                image = b.render(scale=1)
                try:
                    image.to_pil().save(out / f'output-page-{index + 1}.png')
                finally:
                    image.close()
                rows.append(dict(outputPhysicalPage=index + 1, source=label, sourcePhysicalPage=number,
                                 pixelSize=source_raster[0], independentRasterSha256=source_raster[1]))
            finally:
                a.close()
                b.close()
        # A genuinely blank page and retained image XObject; text extraction alone cannot prove these.
        assert not readers['output'].pages[1].extract_text().strip()
        assert len(readers['output'].pages[0].images) == len(readers['first'].pages[2].images) == 1
    finally:
        for document in documents.values():
            document.close()
    result = dict(status='PASS', scope='actual_android_original_page_assembly', pages=rows,
                  archiveSha256=digest(args.archive), verifierSha256=digest(Path(__file__)))
    (out / 'RESULT.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
