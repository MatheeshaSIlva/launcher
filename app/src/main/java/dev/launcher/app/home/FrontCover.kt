package dev.launcher.app.home

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import dev.launcher.app.Reveal

/**
 * Home's old look over its new one while a new wallpaper comes in with new colours (the palette follows the wallpaper):
 * [old] is home's content as it was (the wallpaper left out: [dev.launcher.app.design.ViewPicture.renderClear] of its
 * content layer), and it gives way along the wallpaper reveal's own front ([Reveal]), on the same progress and time as
 * [dev.launcher.app.WallpaperTransition]: where the new wallpaper shows, home shows in its new colours; ahead of the front
 * both are as they were. One reveal for both (the theme's reveal after the wallpaper's played the change twice).
 */
@TargetApi(33)
class FrontCover(old: Bitmap, private val w: Float, private val h: Float, cellPx: Float) {
    private val shader = RuntimeShader(AGSL)
    private val paint = Paint()

    init {
        val o = Reveal.origin(w, h)
        shader.setInputShader("oldImg", BitmapShader(old, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setFloatUniform("cell", cellPx)
        shader.setFloatUniform("origin", o[0], o[1])
        shader.setFloatUniform("maxDist", Reveal.maxDist(w, h))
        paint.shader = shader
    }

    fun draw(canvas: Canvas, progress: Float, time: Float) {
        shader.setFloatUniform("progress", progress)
        shader.setFloatUniform("time", time)
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    private companion object {
        val AGSL = """
uniform shader oldImg;
uniform float progress;
uniform float time;
uniform float cell;
uniform float2 origin;
uniform float maxDist;
""" + Reveal.NOISE + Reveal.FRONT + """
half4 main(float2 coord) {
    // The old content where the old wallpaper still shows, with the front's own soft edge.
    return oldImg.eval(coord) * half(1.0 - revealMix(coord));
}
"""
    }
}
