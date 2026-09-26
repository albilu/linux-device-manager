#!/usr/bin/env python3
"""Generate flatpak-builder file sources for offline Maven builds.

Flathub builders have no network access, so every Maven artifact used by
`mvn -o ... package` must be declared as a pinned `type: file` source.
This script resolves the full closure (project dependencies *and* plugin
dependencies) into the local repository, diffs it, and emits
`maven-sources.json` next to the Flathub submission manifest.

Usage (run from the repository root, once per dependency change):
    python3 packaging/flatpak/flathub/generate-maven-sources.py

The upstream flatpak-maven-generator.py is not published anywhere
verifiable, so this self-contained generator replaces it. Only Maven
Central artifacts are supported; anything resolved from another remote
aborts the run.
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

CENTRAL_BASE = "https://repo.maven.apache.org/maven2"

ROOT = Path(__file__).resolve().parents[3]

# Reactor artifacts are built from source in the sandbox and must never be
# vendored (org.ldm is not on Maven Central).
EXCLUDE_PREFIXES = ("org/ldm/",)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Vendor Maven Central artifacts as flatpak-builder "
                    "file sources for offline Flathub builds.")
    parser.add_argument(
        "--local-repo", default=None,
        help="Resolve into a scratch local repository instead of the "
             "default one. REQUIRED for a complete listing unless the "
             "default repo is empty: only newly downloaded artifacts are "
             "emitted, so a primed ~/.m2 would silently drop most of the "
             "closure. Example: --local-repo /tmp/ldm-m2-plain")
    return parser.parse_args()


def local_repo(explicit: str | None) -> Path:
    if explicit:
        return Path(explicit)
    out = subprocess.run(
        ["mvn", "-q", "-N", "help:evaluate",
         "-Dexpression=settings.localRepository", "-DforceStdout"],
        cwd=ROOT, capture_output=True, text=True, check=True,
    )
    return Path(out.stdout.strip())


def snapshot(repo: Path) -> set[str]:
    return {
        str(p.relative_to(repo))
        for p in repo.rglob("*")
        if p.is_file() and p.suffix in (".jar", ".pom")
    }


def maven_env() -> dict:
    env = dict(os.environ)
    jdk25 = Path("/usr/lib/jvm/java-25-openjdk-amd64")
    if jdk25.is_dir():
        env["JAVA_HOME"] = str(jdk25)
    return env


def resolve(repo: Path) -> None:
    # Mirror the sandbox build exactly: a full `install -DskipTests`
    # (lifecycle plugins included) followed by the copy-dependencies call
    # the manifest uses to assemble lib/. Reactor artifacts (org.ldm) come
    # from the source checkout, everything else must be vendored.
    env = maven_env()
    repo_arg = [f"-Dmaven.repo.local={repo}"]
    subprocess.run(
        ["mvn", "-B", "-q", *repo_arg, "-DskipTests", "install"],
        cwd=ROOT, check=True, env=env)
    subprocess.run(
        ["mvn", "-B", "-q", *repo_arg, "-pl", "gui-gtk",
         "dependency:copy-dependencies",
         "-DincludeScope=runtime",
         "-DoutputDirectory=/tmp/ldm-flatpak-deps-probe"],
        cwd=ROOT, check=True, env=env)


def check_central(repo: Path, rel: str) -> None:
    tracker = repo / rel.rsplit("/", 1)[0] / "_remote.repositories"
    if not tracker.is_file():
        # Present without tracking info: treated by Maven as locally
        # installed, which resolves fine offline. Still pin to Central —
        # the file bytes must match what Central serves.
        return
    text = tracker.read_text(errors="replace")
    name = rel.rsplit("/", 1)[1]
    entries = [ln for ln in text.splitlines()
               if ln.startswith(name + ">") or ln.startswith(name + " ")]
    if not entries or not any("central=" in ln for ln in entries):
        raise SystemExit(
            f"ERROR: {rel} was not resolved from Maven Central "
            f"(see {tracker}). Only Central artifacts can be vendored.")


def main() -> int:
    args = parse_args()
    repo = local_repo(args.local_repo)
    if args.local_repo is None:
        print("WARNING: resolving into the default local repository; the "
              "listing will be incomplete unless it is empty. Prefer "
              "--local-repo /tmp/ldm-m2-plain.", file=sys.stderr)
    else:
        repo.mkdir(parents=True, exist_ok=True)
    before = snapshot(repo)
    resolve(repo)
    new_files = sorted(
        f for f in snapshot(repo) - before
        if not f.startswith(EXCLUDE_PREFIXES)
    )
    if not new_files:
        print("No new artifacts resolved; is the local repo already primed? "
              "Delete it or run with a clean ~/.m2 for a full listing.")
    sources = []
    for rel in new_files:
        check_central(repo, rel)
        path = repo / rel
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        directory, _ = rel.rsplit("/", 1)
        sources.append({
            "type": "file",
            "url": f"{CENTRAL_BASE}/{rel}",
            "sha256": digest,
            "dest": f".m2/repository/{directory}",
        })
    out_path = Path(__file__).resolve().parent / "maven-sources.json"
    out_path.write_text(json.dumps(sources, indent=2) + "\n")
    total = sum((repo / r).stat().st_size for r in new_files)
    print(f"Wrote {len(sources)} sources to {out_path} "
          f"({total / 1024 / 1024:.1f} MiB to fetch at build time)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
