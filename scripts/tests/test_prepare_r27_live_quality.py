import importlib.util
import json
from pathlib import Path
import sys

import pytest

sys.path.insert(0,str(Path(__file__).parents[1]))
spec=importlib.util.spec_from_file_location('quality_prepare',Path(__file__).parents[1]/'prepare_r27_live_quality.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


def test_only_public_prompts_enter_the_pinned_live_manifest(tmp_path):
    output=tmp_path/'public';module.build(output)
    manifest=json.loads((output/'LIVE_INPUT.json').read_text(encoding='utf-8'))
    assert manifest['modelId']=='gpt-6.1-terra' and manifest['reasoning']=='MEDIUM' and manifest['store'] is False
    assert len(manifest['cases'])==37
    assert all(set(row)=={'id','category','prompt'} for row in manifest['cases'])
    assert len({row['id'] for row in manifest['cases']})==37
    assert all('reference' not in row and 'input' not in row and 'expected' not in row for row in manifest['cases'])
    assert (output/'corpus/REFERENCE_RUBRIC.json').is_file()


def test_existing_live_input_and_reference_are_never_overwritten(tmp_path):
    output=tmp_path/'public';module.build(output)
    before=(output/'LIVE_INPUT.json').read_bytes()
    with pytest.raises(FileExistsError):module.build(output)
    assert (output/'LIVE_INPUT.json').read_bytes()==before
