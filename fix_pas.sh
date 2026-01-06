#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/pasiflonet_mobile

ts=$(date +%s)

SW="app/src/main/java/com/pasiflonet/mobile/worker/SendWorker.kt"
OV="app/src/main/java/com/pasiflonet/mobile/ui/editor/OverlayEditorView.kt"

for f in "$SW" "$OV"; do
  if [ -f "$f" ]; then
    cp -f "$f" "$f.bak_$ts"
    echo "Backup: $f -> $f.bak_$ts"
  fi
done

python - <<'PY'
from pathlib import Path
import re

SW = Path("app/src/main/java/com/pasiflonet/mobile/worker/SendWorker.kt")
OV = Path("app/src/main/java/com/pasiflonet/mobile/ui/editor/OverlayEditorView.kt")

def ensure_imports(s: str, imports: list[str]) -> str:
    lines = s.splitlines(True)
    pkg_i = None
    last_imp_i = None
    for i, ln in enumerate(lines):
        st = ln.strip()
        if st.startswith("package "):
            pkg_i = i
        if st.startswith("import "):
            last_imp_i = i
    insert_at = (last_imp_i + 1) if last_imp_i is not None else ((pkg_i + 1) if pkg_i is not None else 0)

    have = {ln.strip() for ln in lines if ln.strip().startswith("import ")}
    add = []
    for imp in imports:
        imp_line = f"import {imp}"
        if imp_line not in have:
            add.append(imp_line + "\n")
    if add:
        lines[insert_at:insert_at] = add + ["\n"]
    return "".join(lines)

def remove_block(s: str, marker: str) -> str:
    idx = s.find(marker)
    if idx < 0:
        return s
    # cut out a safe chunk after marker (up to ~200 lines) to remove injected garbage without rollback
    tail_lines = s[idx:].splitlines(True)
    cut = "".join(tail_lines[:220])
    return s.replace(cut, "\n    // (auto) removed broken injected preview block\n\n", 1)

def patch_send_worker():
    if not SW.exists():
        print("SKIP: SendWorker.kt missing")
        return
    s = SW.read_text(encoding="utf-8", errors="ignore")

    # Fix unresolved Config -> FFmpegKitConfig
    s = re.sub(r"\bConfig\b", "FFmpegKitConfig", s)

    s = ensure_imports(s, [
        "com.arthenica.ffmpegkit.FFmpegKitConfig",
        "com.arthenica.ffmpegkit.Log",
        "com.arthenica.ffmpegkit.Statistics",
        "java.io.File",
    ])

    # Ensure keys exist
    if "companion object" in s and "KEY_LOG_TAIL" not in s:
        s = re.sub(
            r"(companion\s+object\s*\{)",
            r'\1\n        const val KEY_LOG_TAIL = "log_tail"\n        const val KEY_ERROR_MSG = "error_msg"\n        const val KEY_LOG_FILE = "log_file"\n',
            s,
            count=1
        )

    # Inject log callbacks once, right after doWork() {
    m = re.search(r"override\s+fun\s+doWork\s*\(\s*\)\s*:\s*Result\s*\{", s)
    if m and "PAS_FFMPEG_LOGS_BEGIN" not in s:
        inject = r'''
        // PAS_FFMPEG_LOGS_BEGIN
        val logDir = File(applicationContext.getExternalFilesDir(null), "pasiflonet_logs").apply { mkdirs() }
        val logFile = File(logDir, "send_${System.currentTimeMillis()}.log")

        fun pushLine(line: String) {
            runCatching { logFile.appendText(line + "\n") }
            runCatching { setProgressAsync(androidx.work.workDataOf(KEY_LOG_TAIL to line, KEY_LOG_FILE to logFile.absolutePath)) }
        }

        FFmpegKitConfig.enableLogCallback { log: Log ->
            pushLine("[ffmpeg] ${log.level}: ${log.message}")
        }
        FFmpegKitConfig.enableStatisticsCallback { stat: Statistics ->
            pushLine("[stat] time=${stat.time}ms size=${stat.size} bitrate=${stat.bitrate} speed=${stat.speed}")
        }
        // PAS_FFMPEG_LOGS_END

'''
        s = s[:m.end()] + inject + s[m.end():]

    SW.write_text(s, encoding="utf-8")
    print("OK: patched SendWorker.kt")

def patch_overlay():
    if not OV.exists():
        print("SKIP: OverlayEditorView.kt missing")
        return
    s = OV.read_text(encoding="utf-8", errors="ignore")

    # remove ONLY injected broken blocks
    if "AUTO_BLUR_PREVIEW_REFLECTION" in s:
        s = remove_block(s, "AUTO_BLUR_PREVIEW_REFLECTION")
    if "PAS_DRAW_BLUR_RECT_PREVIEW_V2" in s:
        s = remove_block(s, "PAS_DRAW_BLUR_RECT_PREVIEW_V2")

    # remove accidental python tokens if ever inserted
    s = re.sub(r"^\s*(def\s+|True\b|False\b|None\b).*\n", "", s, flags=re.M)

    OV.write_text(s, encoding="utf-8")
    print("OK: patched OverlayEditorView.kt")

patch_send_worker()
patch_overlay()
PY

if [ -n "$(git status --porcelain)" ]; then
  git add "$SW" "$OV" || true
  git commit -m "Hotfix: FFmpegKitConfig live logs+device log file + cleanup broken overlay injections" || true
  git push
  echo "DONE: pushed"
else
  echo "No changes to commit."
fi
