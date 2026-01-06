from pathlib import Path
import re

ROOT = Path("app/src/main/java")
SW = ROOT / "com/pasiflonet/mobile/worker/SendWorker.kt"
OV = ROOT / "com/pasiflonet/mobile/ui/editor/OverlayEditorView.kt"

def die(msg):
    raise SystemExit("ERR: " + msg)

def patch_sendworker():
    if not SW.exists():
        die(f"missing {SW}")

    s = SW.read_text(encoding="utf-8", errors="replace")

    # 1) Make watermark smaller + safe (cap by w/h, keep aspect)
    # Replace any scale2ref that uses main_w*0.18 (or similar) to 0.12 and add force_original_aspect_ratio=decrease
    s2 = s
    s2 = re.sub(
        r"scale2ref=w=main_w\*0\.(?:18|17|16|15|14|13|12)\s*:h=-1",
        "scale2ref=w=main_w*0.12:h=main_h*0.12:force_original_aspect_ratio=decrease",
        s2,
    )
    s2 = re.sub(
        r"scale2ref=w=main_w\*0\.(?:18|17|16|15|14|13|12):h=-1",
        "scale2ref=w=main_w*0.12:h=main_h*0.12:force_original_aspect_ratio=decrease",
        s2,
    )

    # 2) Support local edited media via KEY_MEDIA_URI/KEY_MEDIA_MIME (fix "video sends text only")
    # Insert a branch after the "!sendWithMedia" block (right before src ids check)
    if "KEY_MEDIA_URI" in s2 and "val localMediaUriStr" not in s2:
        anchor = r'if\s*\(!sendWithMedia\)\s*\{[\s\S]*?return Result\.success\(\)\s*\}'
        m = re.search(anchor, s2)
        if not m:
            # fallback anchor: right before src ids check
            m2 = re.search(r"if\s*\(srcChatId\s*==\s*0L\s*\|\|\s*srcMsgId\s*==\s*0L\)", s2)
            if not m2:
                die("cannot find insertion point for local media branch in SendWorker.kt")
            ins_at = m2.start()
        else:
            ins_at = m.end()

        insert = """

            // === Local media (from editor) support ===
            val localMediaUriStr = inputData.getString(KEY_MEDIA_URI).orEmpty().trim()
            val localMediaMime = inputData.getString(KEY_MEDIA_MIME).orEmpty().trim()

            if (localMediaUriStr.isNotBlank()) {
                val uri = Uri.parse(localMediaUriStr)

                val kindFromMime = when {
                    localMediaMime.equals("image/gif", ignoreCase = true) -> Kind.ANIMATION
                    localMediaMime.startsWith("image/", ignoreCase = true) -> Kind.PHOTO
                    localMediaMime.startsWith("video/", ignoreCase = true) -> Kind.VIDEO
                    else -> Kind.DOCUMENT
                }

                val inExt = when (kindFromMime) {
                    Kind.PHOTO -> "jpg"
                    Kind.VIDEO -> "mp4"
                    Kind.ANIMATION -> "mp4"
                    else -> "bin"
                }

                val inputFileLocal = resolveUriToTempFile(uri, tmpDir, "in_${System.currentTimeMillis()}.$inExt")
                    ?: run {
                        logE("local media_uri open failed: $localMediaUriStr")
                        pushLine("RETURN: Result.failure (media_uri open failed)")
                        return Result.failure()
                    }

                // watermark file (אם יש)
                val wmFileLocal: File? = if (watermarkUriStr.isNotBlank()) {
                    resolveUriToTempFile(Uri.parse(watermarkUriStr), tmpDir, "wm_${System.currentTimeMillis()}.png")
                } else null

                // blur rects
                val rectsLocal = parseRects(blurRectsStr)
                val needEditsLocal = (wmFileLocal != null) || rectsLocal.isNotEmpty()

                val finalFileLocal: File = if (!needEditsLocal) {
                    inputFileLocal
                } else {
                    val outExt = when (kindFromMime) {
                        Kind.PHOTO -> "jpg"
                        Kind.VIDEO -> "mp4"
                        Kind.ANIMATION -> "mp4"
                        else -> "bin"
                    }
                    val outFile = File(tmpDir, "out_${System.currentTimeMillis()}.$outExt")
                    val ok = runFfmpegEdits(
                        input = inputFileLocal,
                        output = outFile,
                        kind = kindFromMime,
                        rects = rectsLocal,
                        wmFile = wmFileLocal,
                        wmX = wmX,
                        wmY = wmY
                    )
                    if (!ok) inputFileLocal else outFile
                }

                val input = TdApi.InputFileLocal(finalFileLocal.absolutePath)
                val content: TdApi.InputMessageContent = when (kindFromMime) {
                    Kind.PHOTO -> TdApi.InputMessagePhoto().apply {
                        photo = input
                        caption = captionFmt
                    }
                    Kind.VIDEO -> TdApi.InputMessageVideo().apply {
                        video = input
                        caption = captionFmt
                        supportsStreaming = true
                    }
                    Kind.ANIMATION -> TdApi.InputMessageAnimation().apply {
                        animation = input
                        caption = captionFmt
                    }
                    else -> TdApi.InputMessageDocument().apply {
                        document = input
                        caption = captionFmt
                    }
                }

                val sentOk = sendMessage(targetChatId, content)
                logI("sent LOCAL media kind=$kindFromMime edited=$needEditsLocal final=${finalFileLocal.name} ok=$sentOk")
                return if (sentOk) Result.success() else Result.failure()
            }

"""
        s2 = s2[:ins_at] + insert + s2[ins_at:]

    if s2 != s:
        SW.write_text(s2, encoding="utf-8")
        print("OK: patched SendWorker.kt (watermark scale + local media_uri support)")
    else:
        print("OK: SendWorker.kt unchanged (already patched?)")

def patch_overlay():
    if not OV.exists():
        die(f"missing {OV}")
    s = OV.read_text(encoding="utf-8", errors="replace")
    s2 = s

    # Keep preview watermark consistent with worker: 0.12 instead of 0.18
    s2 = re.sub(r"dst\.width\(\)\s*\*\s*0\.18f", "dst.width() * 0.12f", s2)

    # Add a safe exporter for blur rects so the Activity can pass exact format "l,t,r,b;..."
    if "fun exportBlurRects" not in s2:
        # insert before last closing brace of class
        idx = s2.rfind("}")
        if idx == -1:
            die("OverlayEditorView.kt malformed (no closing brace)")
        insert = """

    // Export blur rects as "l,t,r,b;..." in 0..1 normalized coordinates (matches SendWorker.parseRects)
    fun exportBlurRects(): String {
        return try {
            val out = mutableListOf<String>()
            val fieldNames = listOf("blurRects", "mBlurRects", "rects", "blurRectList")
            for (fn in fieldNames) {
                val f = runCatching { this::class.java.getDeclaredField(fn).apply { isAccessible = true } }.getOrNull()
                    ?: continue
                val v = runCatching { f.get(this) }.getOrNull() ?: continue

                val list = when (v) {
                    is java.util.List<*> -> v
                    else -> continue
                }

                for (it in list) {
                    if (it == null) continue
                    when (it) {
                        is android.graphics.RectF -> {
                            val n = rectPxToNorm(it).norm()
                            out += "${n.l},${n.t},${n.r},${n.b}"
                        }
                        is android.graphics.Rect -> {
                            val rf = android.graphics.RectF(it)
                            val n = rectPxToNorm(rf).norm()
                            out += "${n.l},${n.t},${n.r},${n.b}"
                        }
                        else -> {
                            val c = it.javaClass
                            fun gf(n: String): Float? = runCatching {
                                val ff = c.getDeclaredField(n).apply { isAccessible = true }
                                (ff.get(it) as? Number)?.toFloat()
                            }.getOrNull()
                            val l = gf("l"); val t = gf("t"); val r = gf("r"); val b = gf("b")
                            if (l != null && t != null && r != null && b != null) {
                                val ll = l.coerceIn(0f, 1f)
                                val tt = t.coerceIn(0f, 1f)
                                val rr = r.coerceIn(0f, 1f)
                                val bb = b.coerceIn(0f, 1f)
                                if (rr > ll && bb > tt) out += "$ll,$tt,$rr,$bb"
                            }
                        }
                    }
                }
            }
            out.distinct().joinToString(";")
        } catch (_: Throwable) {
            ""
        }
    }

"""
        s2 = s2[:idx] + insert + "\n" + s2[idx:]
    if s2 != s:
        OV.write_text(s2, encoding="utf-8")
        print("OK: patched OverlayEditorView.kt (wm preview scale + exportBlurRects)")
    else:
        print("OK: OverlayEditorView.kt unchanged (already patched?)")

def patch_any_sender_that_sets_blur_rects():
    # Find any Kotlin file that sets KEY_BLUR_RECTS and replace value to view.exportBlurRects() if possible
    files = list(ROOT.rglob("*.kt"))
    touched = 0
    for p in files:
        txt = p.read_text(encoding="utf-8", errors="replace")
        if "KEY_BLUR_RECTS" not in txt:
            continue

        # try to detect OverlayEditorView variable name
        m = re.search(r"val\s+(\w+)\s*=\s*findViewById<\s*OverlayEditorView\s*>\(", txt)
        view_name = m.group(1) if m else None
        if not view_name:
            # other common pattern
            m2 = re.search(r"(\w+)\s*:\s*OverlayEditorView", txt)
            view_name = m2.group(1) if m2 else None

        if not view_name:
            continue

        # Replace occurrences like: KEY_BLUR_RECTS to something   ==> KEY_BLUR_RECTS to view.exportBlurRects()
        new = re.sub(
            r"(KEY_BLUR_RECTS\s+to\s+)([^,\)\n]+)",
            lambda mm: mm.group(1) + f"{view_name}.exportBlurRects()",
            txt,
        )
        # Also handle putString(KEY_BLUR_RECTS, something)
        new = re.sub(
            r"(putString\(\s*KEY_BLUR_RECTS\s*,\s*)([^,\)\n]+)(\s*\))",
            lambda mm: mm.group(1) + f"{view_name}.exportBlurRects()" + mm.group(3),
            new,
        )

        if new != txt:
            p.write_text(new, encoding="utf-8")
            touched += 1

    print(f"OK: patched blur rects sender files: {touched}")

if __name__ == "__main__":
    patch_sendworker()
    patch_overlay()
    patch_any_sender_that_sets_blur_rects()
