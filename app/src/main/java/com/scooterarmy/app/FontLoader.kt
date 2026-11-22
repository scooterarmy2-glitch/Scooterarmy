package com.scooterarmy.app

import android.content.Context
import android.graphics.Typeface
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File

/**
 * Runtime font loading. Orbitron / Share Tech Mono ship as base64 text
 * files under res/raw (the GitHub connector cannot push binary .ttf files).
 * On first use each one is decoded to a .ttf file in internal storage and
 * loaded with Typeface.createFromFile, then cached. Decoding happens once
 * per install — if the file already exists it is reused as-is.
 *
 * There is deliberately NO build-time font step anymore. Decoding fonts
 * during the build failed four straight builds on Gradle task-wiring
 * validation, so that whole failure class was removed: fonts are a
 * runtime concern now. Layouts use a plain system fontFamily plus an
 * android:tag ("font_orbitron_900" etc.); applyFonts() walks a view tree
 * and swaps in the real Typeface wherever it finds the tag. The look is
 * unchanged — Orbitron for display text, Share Tech Mono for mono text —
 * just applied in code instead of XML.
 */
object FontLoader {
    private val cache = mutableMapOf<String, Typeface?>()
    private const val DIR = "fonts"

    fun orbitron400(ctx: Context): Typeface? = load(ctx, R.raw.orbitron_400, "orbitron_400.ttf")
    fun orbitron700(ctx: Context): Typeface? = load(ctx, R.raw.orbitron_700, "orbitron_700.ttf")
    fun orbitron900(ctx: Context): Typeface? = load(ctx, R.raw.orbitron_900, "orbitron_900.ttf")
    fun shareTechMono(ctx: Context): Typeface? = load(ctx, R.raw.share_tech_mono, "share_tech_mono.ttf")

    private fun load(ctx: Context, rawRes: Int, fileName: String): Typeface? {
        cache[fileName]?.let { return it }
        val tf = try {
            val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
            val file = File(dir, fileName)
            if (!file.exists() || file.length() == 0L) {
                val b64 = ctx.resources.openRawResource(rawRes).bufferedReader().readText()
                    .replace(Regex("\\s"), "")
                file.writeBytes(Base64.decode(b64, Base64.DEFAULT))
            }
            Typeface.createFromFile(file)
        } catch (_: Exception) {
            null
        }
        cache[fileName] = tf
        return tf
    }

    /** Walk a view tree; any TextView tagged font_orbitron_900 etc. gets the real Typeface. */
    fun applyFonts(root: View) {
        if (root is TextView) {
            when (root.tag) {
                "font_orbitron_400" -> orbitron400(root.context)?.let { root.typeface = it }
                "font_orbitron_700" -> orbitron700(root.context)?.let { root.typeface = it }
                "font_orbitron_900" -> orbitron900(root.context)?.let { root.typeface = it }
                "font_share_tech_mono" -> shareTechMono(root.context)?.let { root.typeface = it }
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) applyFonts(root.getChildAt(i))
        }
    }
}
