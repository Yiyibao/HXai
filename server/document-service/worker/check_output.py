"""Fresh container verifies image decoding after the parser has stopped."""
import json
from pathlib import Path, PurePosixPath

from PIL import Image


def check():
    root = Path("/output")
    manifest = root / "manifest.json"
    if manifest.is_symlink() or manifest.stat().st_size > 2 * 1024 * 1024:
        raise ValueError("invalid_manifest")
    assets = json.loads(manifest.read_text())["assets"]
    if len(assets) > 2000:
        raise ValueError("asset_limit")
    total = 0
    for asset in assets:
        path = PurePosixPath(asset["path"])
        if len(path.parts) != 1 or path.is_absolute() or ".." in path.parts or ":" in str(path) or "\\" in str(path):
            raise ValueError("unsafe_path")
        file = root / str(path)
        if file.is_symlink():
            raise ValueError("unsafe_path")
        total += file.stat().st_size
        if total > 64 * 1024 * 1024:
            raise ValueError("output_limit")
        if asset["mediaType"] == "image/png":
            with Image.open(file) as image:
                if image.format != "PNG" or image.width * image.height > 32_000_000:
                    raise ValueError("image_limit")
                image.load()


if __name__ == "__main__":
    check()
