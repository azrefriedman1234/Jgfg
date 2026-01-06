from pathlib import Path

p = Path("app/src/main/java/com/pasiflonet/mobile/worker/SendWorker.kt")
s = p.read_text(encoding="utf-8")

marker = "private fun runFfmpegEdits("
idx = s.find(marker)
if idx == -1:
    print("ERR: marker not found:", marker)
    raise SystemExit(1)

# מוצאים את הסוגר האחרון '}' בסוף הקובץ (סוגר את המחלקה)
last_brace = s.rfind("}")
if last_brace <= idx:
    print("ERR: last brace before fun, abort")
    raise SystemExit(1)

new_fun = """    private fun runFfmpegEdits(
        input: File,
        output: File,
        kind: Kind,
        rects: List<RectN>,
        wmFile: File?,
        wmX: Float,
        wmY: Float
    ): Boolean {
        val hasWm = wmFile != null

        val filters = mutableListOf<String>()
        var cur = "v0"

        // base: always go to rgba
        filters += "[0:v]format=rgba[$cur]"

        // blur rectangles
        rects.forEachIndexed { i, r ->
            val base = "base$i"
            val tmp = "tmp$i"
            val bl = "bl$i"
            val out = "v${i + 1}"

            val xCrop = "max(0,${r.l}*iw)"
            val yCrop = "max(0,${r.t}*ih)"
            val wCrop = "max(1,(${r.r}-${r.l})*iw)"
            val hCrop = "max(1,(${r.b}-${r.t})*ih)"
            val xOv = "max(0,${r.l}*main_w)"
            val yOv = "max(0,${r.t}*main_h)"

            filters += "[$cur]split=2[$base][$tmp]"
            filters += "[$tmp]crop=w=$wCrop:h=$hCrop:x=$xCrop:y=$yCrop,boxblur=10:1[$bl]"
            filters += "[$base][$bl]overlay=x=$xOv:y=$yOv[$out]"
            cur = out
        }

        val outLabel = if (hasWm) "outv" else cur

        // watermark overlay (if exists)
        if (hasWm && wmFile != null) {
            val xExpr = "(${wmX.coerceIn(0f, 1f)}*(main_w-overlay_w))"
            val yExpr = "(${wmY.coerceIn(0f, 1f)}*(main_h-overlay_h))"

            val vScaled = "vwm"
            // scale watermark relative to current video
            filters += "[$cur][1:v]scale2ref=w=iw*0.18:h=-1[$vScaled][wm]"
            // simple overlay using xExpr/yExpr
            filters += "[$vScaled][wm]overlay=$xExpr:$yExpr[$outLabel]"
        }

        val fc = filters.joinToString(";")

        val args = mutableListOf<String>()
        args += "-y"
        args += "-i"; args += input.absolutePath
        if (hasWm && wmFile != null) {
            args += "-i"; args += wmFile.absolutePath
        }
        args += "-filter_complex"; args += fc
        args += "-map"; args += "[$outLabel]"

        when (kind) {
            Kind.PHOTO -> {
                args += "-q:v"; args += "2"
                args += output.absolutePath
            }
            Kind.VIDEO, Kind.ANIMATION -> {
                // keep audio if present
                args += "-map"; args += "0:a?"
                args += "-c:v"; args += "libx264"
                args += "-preset"; args += "veryfast"
                args += "-crf"; args += "28"
                args += "-c:a"; args += "aac"
                args += "-b:a"; args += "128k"
                args += output.absolutePath
            }
            else -> {
                args += output.absolutePath
            }
        }

        val cmd = args.joinToString(" ")
        logI("FFmpeg cmd: $cmd")

        val session = FFmpegKit.execute(cmd)
        val rc = session.returnCode
        val ok = ReturnCode.isSuccess(rc)

        if (!ok) {
            logE("FFmpeg failed rc=$rc")
            logE("FFmpeg logs:\\n" + session.allLogsAsString)
        } else {
            logI("FFmpeg OK -> ${output.name} size=${output.length()}")
        }

        return ok && output.exists() && output.length() > 0
    }
"""

# מחליפים מההתחלה של הפונקציה עד הסוגר האחרון
new_s = s[:idx] + new_fun + "\n}\n"
p.write_text(new_s, encoding="utf-8")
print("OK: rewritten runFfmpegEdits() and class closing brace")
