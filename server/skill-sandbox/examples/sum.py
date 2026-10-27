"""Example explicitly approved Skill. stdin JSON -> stdout JSON; no runtime installs."""
import json
import sys

data = json.load(sys.stdin)
values = data['values']
assert len(values) <= 1000 and all(isinstance(x, (int, float)) for x in values)
print(json.dumps({'sum': sum(values)}, allow_nan=False))
