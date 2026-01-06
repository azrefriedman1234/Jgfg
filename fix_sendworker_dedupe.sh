#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/pasiflonet_mobile

SW="app/src/main/java/com/pasiflonet/mobile/worker/SendWorker.kt"
ts=$(date +%s)

cp -f "$SW" "$SW.bak_$ts"
echo "Backup created: $SW.bak_$ts"

python - <<'PY'
from pathlib import Path
import re

p = Path("app/src/main/java/com/pasiflonet/mobile/worker/SendWorker.kt")
s = p.read_text(encoding="utf-8", errors="ignore")

# 1) להסיר import בעייתי של Log (להשאיר android.util.Log בלבד)
s = re.sub(r'^import\s+com\.arthenica\.ffmpegkit\.Log\s*\n', '', s, flags=re.M)

# 2) להסיר כל הזרקה כפולה – להשאיר רק הראשונה
def dedupe(block_start):
    parts = s.split(block_start)
    if len(parts) <= 2:
        return s
    return parts[0] + block_start + parts[1]

markers = [
    "// PAS_FFMPEG_LOGS_BEGIN",
]

for m in markers:
    if s.count(m) > 1:
        s = dedupe(m)

# 3) למחוק הגדרות כפולות של logDir / logFile / pushLine
s = re.sub(
    r'(val logDir: File[\s\S]*?pushLine\(line: String\)[\s\S]*?\})',
    r'\1',
    s,
    count=1
)

# להסיר עותקים נוספים
s = re.sub(
    r'\n\s*val logDir: File[\s\S]*?pushLine\(line: String\)[\s\S]*?\}',
    '',
    s
)

# 4) לוודא שמשתמשים ב־android.util.Log במפורש
s = s.replace("Log.", "android.util.Log.")

p.write_text(s, encoding="utf-8")
print("OK: SendWorker deduped, Log conflict fixed")
PY

git add "$SW"
git commit -m "Hotfix: dedupe SendWorker FFmpeg logs + fix Log conflict (single pushLine)"
git push

echo "DONE"
