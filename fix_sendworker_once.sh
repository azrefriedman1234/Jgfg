#!/data/data/com.termux/files/usr/bin/bash
set -e

SW="app/src/main/java/com/pasiflonet/mobile/worker/SendWorker.kt"
ts=$(date +%s)
cp -f "$SW" "$SW.bak_$ts"
echo "Backup: $SW.bak_$ts"

python - <<'PY'
from pathlib import Path
import re

p = Path("app/src/main/java/com/pasiflonet/mobile/worker/SendWorker.kt")
s = p.read_text(encoding="utf-8", errors="ignore")

# --- 1. imports: להסיר כל Log בעייתי ---
s = re.sub(r'^import\s+com\.arthenica\.ffmpegkit\.Log\s*\n', '', s, flags=re.M)

# --- 2. לאתר doWork ---
m = re.search(r'override\s+fun\s+doWork\s*\(\s*\)\s*:\s*Result\s*\{', s)
if not m:
    raise SystemExit("doWork() not found")

start = s.find("{", m.end()-1)
depth = 1
i = start + 1
while i < len(s) and depth:
    if s[i] == "{": depth += 1
    elif s[i] == "}": depth -= 1
    i += 1
end = i

# --- 3. גוף חדש, יחיד, תקין ---
body = r'''
        val logDir = File(applicationContext.getExternalFilesDir(null), "pasiflonet_logs").apply { mkdirs() }
        val logFile = File(logDir, "send_${System.currentTimeMillis()}.log")
        val tail = ArrayDeque<String>(200)

        fun pushLine(line: String) {
            runCatching { logFile.appendText(line + "\n") }
            if (tail.size >= 200) tail.removeFirst()
            tail.addLast(line.take(500))
            setProgressAsync(workDataOf(KEY_LOG_TAIL to tail.joinToString("\n")))
        }

        try {
            pushLine("=== SendWorker started ===")

            com.arthenica.ffmpegkit.FFmpegKitConfig.enableLogCallback { log ->
                pushLine("[FFMPEG ${'$'}{log.level}] ${'$'}{log.message}")
            }

            // === EXISTING SEND LOGIC CONTINUES BELOW ===
'''

# שומרים את הקוד המקורי של השליחה (בלי לוגים כפולים)
old_body = s[start+1:end-1]
old_body = re.sub(r'val logDir[\s\S]*?pushLine\([\s\S]*?\}', '', old_body)
old_body = re.sub(r'FFmpegKitConfig\.enableLogCallback[\s\S]*?\}', '', old_body)

tail = r'''
            pushLine("=== SendWorker finished ===")
            return Result.success(workDataOf(KEY_LOG_FILE to logFile.absolutePath))
        } catch (t: Throwable) {
            pushLine("FAILED: " + (t.message ?: t.javaClass.simpleName))
            pushLine(android.util.Log.getStackTraceString(t))
            return Result.failure(
                workDataOf(
                    KEY_ERROR_MSG to (t.message ?: "send failed"),
                    KEY_LOG_FILE to logFile.absolutePath,
                    KEY_LOG_TAIL to tail.joinToString("\n")
                )
            )
        }
'''

new = s[:start+1] + body + old_body + tail + s[end:]
p.write_text(new, encoding="utf-8")
print("OK: SendWorker.doWork() rebuilt cleanly (single logger, single FFmpeg callback)")
PY

git add "$SW"
git commit -m "Fix-forward: rebuild SendWorker.doWork (single FFmpeg logger, no duplicates)"
git push
echo "DONE"
