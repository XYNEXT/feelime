#!/usr/bin/env python3
"""Describe and verify the exact Bazel repository cache used by the pinned Mozc build."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--module-graph", type=Path, required=True)
    parser.add_argument("--repository-cache", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    lock = json.loads(args.lock.read_text())
    selected = sorted(set(re.findall(
        r"([A-Za-z0-9_.+-]+)@([A-Za-z0-9_.+-]+)",
        args.module_graph.read_text(),
    )))
    selected = [(name, version) for name, version in selected if name != "mozc"]
    registry_hashes = lock["registryFileHashes"]
    content = args.repository_cache / "content_addressable" / "sha256"

    modules = []
    referenced_cache_hashes: set[str] = set()
    for name, version in selected:
        source_url = f"https://bcr.bazel.build/modules/{name}/{version}/source.json"
        source_hash = registry_hashes[source_url]
        source_file = content / source_hash / "file"
        if digest(source_file) != source_hash:
            raise SystemExit(f"registry metadata mismatch: {source_url}")
        source = json.loads(source_file.read_text())
        integrity = source.get("integrity", "")
        if not integrity.startswith("sha256-"):
            raise SystemExit(f"unsupported source integrity: {name}@{version}")
        archive_hash = base64.b64decode(integrity.removeprefix("sha256-")).hex()
        archive = content / archive_hash / "file"
        materialized = archive.is_file()
        if materialized and digest(archive) != archive_hash:
            raise SystemExit(f"source archive mismatch: {name}@{version}")
        referenced_cache_hashes.add(source_hash)
        if materialized:
            referenced_cache_hashes.add(archive_hash)
        modules.append({
            "name": name,
            "version": version,
            "canonicalId": f"bcr:{name}@{version}",
            "registryMetadata": {
                "url": source_url,
                "bytes": source_file.stat().st_size,
                "sha256": source_hash,
            },
            "archive": {
                "urls": source.get("url") or source.get("urls"),
                "integrity": integrity,
                "materializedForTargets": materialized,
                "bytes": archive.stat().st_size if materialized else None,
                "sha256": archive_hash,
                "stripPrefix": source.get("strip_prefix"),
                "archiveType": source.get("archive_type"),
            },
            "patches": source.get("patches", {}),
        })

    cache_files = []
    for path in sorted(content.glob("*/file")):
        value = digest(path)
        if value != path.parent.name:
            raise SystemExit(f"content-address mismatch: {path}")
        cache_files.append({
            "sha256": value,
            "bytes": path.stat().st_size,
            "referencedBySelectedModule": value in referenced_cache_hashes,
        })

    # Bazel 9's repository cache also contains canonical-ID lookup records
    # under contents/.  Copying only content_addressable/ is insufficient for
    # a download-disabled replay even when every payload byte is present.
    # Bind every regular cache file so the seed can be reconstructed exactly.
    repository_files = []
    for path in sorted(args.repository_cache.rglob("*")):
        if path.is_symlink():
            target = os.readlink(path)
            resolved = (path.parent / target).resolve()
            if Path(target).is_absolute() or not resolved.is_relative_to(args.repository_cache.resolve()):
                raise SystemExit(f"unsafe repository-cache symlink: {path} -> {target}")
            repository_files.append({
                "path": path.relative_to(args.repository_cache).as_posix(),
                "type": "symlink",
                "target": target,
            })
        elif path.is_file():
            repository_files.append({
                "path": path.relative_to(args.repository_cache).as_posix(),
                "type": "file",
                "bytes": path.stat().st_size,
                "sha256": digest(path),
            })

    result = {
        "schemaVersion": 2,
        "bazelModuleLockSha256": digest(args.lock),
        "moduleGraphSha256": digest(args.module_graph),
        "selectedModules": modules,
        "cacheFiles": cache_files,
        "cacheFileCount": len(cache_files),
        "cacheBytes": sum(item["bytes"] for item in cache_files),
        "repositoryFiles": repository_files,
        "repositoryFileCount": len(repository_files),
        "repositoryBytes": sum(item.get("bytes", 0) for item in repository_files),
        "verification": "Every content-addressed payload filename equals the SHA-256 of its file; every selected BCR source archive matches source.json integrity; every canonical-ID lookup record under contents/ is also byte-bound for offline replay.",
    }
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
