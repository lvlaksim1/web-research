#!/usr/bin/env python3
import argparse
import os
import re
import subprocess
from pathlib import Path

START = "<!-- AUTO-RELEASE-START -->"
END = "<!-- AUTO-RELEASE-END -->"

def run(*args: str) -> str:
    return subprocess.check_output(args, text=True).strip()

def replace_block(path: Path, body: str) -> None:
    text = path.read_text(encoding="utf-8")
    block = f"{START}\n{body.rstrip()}\n{END}"
    if START in text and END in text:
        pattern = re.compile(re.escape(START) + r".*?" + re.escape(END), re.S)
        text = pattern.sub(block, text, count=1)
    else:
        lines = text.splitlines()
        insert_at = 1 if lines and lines[0].startswith("# ") else 0
        lines[insert_at:insert_at] = ["", block, ""]
        text = "\n".join(lines).rstrip() + "\n"
    path.write_text(text, encoding="utf-8")

def numeric_tags() -> list[tuple[int, str]]:
    tags = []
    for tag in run("git", "tag", "--list", "v[0-9]*").splitlines():
        match = re.fullmatch(r"v([0-9]+)", tag.strip())
        if match:
            tags.append((int(match.group(1)), tag.strip()))
    return sorted(tags)

def previous_tag(current_number: int) -> str:
    candidates = [tag for number, tag in numeric_tags() if number < current_number]
    return candidates[-1] if candidates else ""

def release_changes(previous: str, commit: str) -> tuple[list[str], list[str]]:
    commit_range = f"{previous}..{commit}" if previous else commit
    subjects = run("git", "log", "--reverse", "--format=%s", commit_range).splitlines()
    subjects = [
        subject for subject in subjects
        if subject.strip() and not subject.lower().startswith("docs: update release documentation")
    ]
    if previous:
        files = run("git", "diff", "--name-only", previous, commit).splitlines()
    else:
        files = run("git", "show", "--pretty=", "--name-only", commit).splitlines()
    files = [f for f in files if f and f not in {"README.md", "REFACTORING.md"}]
    return subjects, sorted(dict.fromkeys(files))

def application_id(root: Path) -> str:
    gradle = (root / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    match = re.search(r'applicationId\s*=\s*"([^"]+)"', gradle)
    return match.group(1) if match else "unknown"

def subject_bullets(items: list[str], empty: str) -> str:
    if not items:
        return f"- {empty}"
    return "\n".join(f"- {item}" for item in items)

def file_bullets(items: list[str], empty: str) -> str:
    if not items:
        return f"- {empty}"
    return "\n".join(f"- `{item}`" for item in items)

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--release-commit", required=True)
    parser.add_argument("--artifact-filename", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--published-at", default="")
    args = parser.parse_args()

    match = re.fullmatch(r"v([0-9]+)", args.release_tag)
    if not match:
        raise SystemExit(f"Invalid release tag: {args.release_tag}")
    number = int(match.group(1))
    if not re.fullmatch(r"[0-9a-fA-F]{64}", args.sha256):
        raise SystemExit("Invalid SHA-256")

    root = Path(__file__).resolve().parents[2]
    previous = previous_tag(number)
    subjects, files = release_changes(previous, args.release_commit)
    package_id = application_id(root)
    repo = os.environ.get("GITHUB_REPOSITORY", "lvlaksim1/web-research")
    release_url = f"https://github.com/{repo}/releases/tag/{args.release_tag}"
    apk_url = f"https://github.com/{repo}/releases/download/{args.release_tag}/{args.artifact_filename}"
    previous_text = previous or "—"
    published = args.published_at or "—"

    readme_block = f"""## Текущий релиз

- Версия: **{args.release_tag}**
- `versionCode`: **{number}**
- `versionName`: **{args.release_tag}**
- package: `{package_id}`
- commit: `{args.release_commit}`
- APK: `{args.artifact_filename}`
- SHA-256: `{args.sha256}`
- Опубликован: `{published}`
- Предыдущий релиз: **{previous_text}**
- Release: {release_url}
- APK: {apk_url}

### Изменения относительно {previous_text}

{subject_bullets(subjects, "Отдельных изменений между релизами не зафиксировано.")}

### Изменённые файлы

{file_bullets(files, "Нет файловых изменений.")}"""

    refactoring_block = f"""## Состояние на {args.release_tag}

- Релизный commit: `{args.release_commit}`
- Предыдущая контрольная точка: **{previous_text}**
- APK: `{args.artifact_filename}`
- SHA-256: `{args.sha256}`

### Изменения между {previous_text} и {args.release_tag}

{subject_bullets(subjects, "Отдельных изменений между релизами не зафиксировано.")}

### Затронутые файлы

{file_bullets(files, "Нет файловых изменений.")}"""

    replace_block(root / "README.md", readme_block)
    replace_block(root / "REFACTORING.md", refactoring_block)

if __name__ == "__main__":
    main()
