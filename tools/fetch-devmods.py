#!/usr/bin/env python3
"""Downloads the integration mods listed in tools/devmods.json from Modrinth into devmods/ and checks their SHA-512.

Usage: python tools/fetch-devmods.py [--target DIR]

Existing jars with the right hash are kept. Other jars of the same mods are removed from the target, so the build
(which picks the newest matching jar by name) sees exactly the pinned set.
"""
import argparse
import hashlib
import json
import pathlib
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
PREFIXES = {
    "fabric-api": "fabric-api-",
    "sodium": "sodium-fabric-",
    "iris": "iris-fabric-",
    "distanthorizons": "DistantHorizons-fabric-",
}


def sha512(path: pathlib.Path) -> str:
    digest = hashlib.sha512()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--target", default=str(ROOT / "devmods"), help="directory to download into (default: devmods/)")
    args = parser.parse_args()
    target = pathlib.Path(args.target)
    target.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((ROOT / "tools" / "devmods.json").read_text(encoding="utf-8"))
    for mod in manifest["mods"]:
        dest = target / mod["saveAs"]
        prefix = PREFIXES[mod["project"]]
        for other in target.glob(prefix + "*.jar"):
            if other != dest:
                other.unlink()
        if dest.is_file() and sha512(dest) == mod["sha512"]:
            print(f"ok       {dest.name}")
            continue
        request = urllib.request.Request(mod["url"], headers={"User-Agent": "Slipway-build (github.com/TimStewartJ/slipway)"})
        with urllib.request.urlopen(request, timeout=120) as response:
            data = response.read()
        if hashlib.sha512(data).hexdigest() != mod["sha512"]:
            print(f"SHA-512 mismatch for {mod['url']}", file=sys.stderr)
            return 1
        dest.write_bytes(data)
        print(f"fetched  {dest.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
