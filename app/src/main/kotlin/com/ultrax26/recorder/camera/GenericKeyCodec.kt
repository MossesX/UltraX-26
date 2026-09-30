package com.ultrax26.recorder.camera

import android.graphics.Point
import android.graphics.Rect
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.util.Range
import android.util.Rational
import android.util.Size
import com.ultrax26.recorder.settings.KeyType
import kotlin.math.roundToInt

/**
 * Text <-> typed value conversion for *any* Camera2 key, so the "All camera keys" editor can set
 * every request key the HAL advertises (including Samsung vendor tags) without per-key UI code.
 */
object GenericKeyCodec {

    fun javaClass(type: KeyType): Class<*> = when (type) {
        KeyType.INT -> Int::class.javaObjectType
        KeyType.LONG -> Long::class.javaObjectType
        KeyType.FLOAT -> Float::class.javaObjectType
        KeyType.DOUBLE -> Double::class.javaObjectType
        KeyType.BOOLEAN -> Boolean::class.javaObjectType
        KeyType.BYTE -> Byte::class.javaObjectType
        KeyType.INT_ARRAY -> IntArray::class.java
        KeyType.FLOAT_ARRAY -> FloatArray::class.java
        KeyType.LONG_ARRAY -> LongArray::class.java
        KeyType.BYTE_ARRAY -> ByteArray::class.java
        KeyType.BOOLEAN_ARRAY -> BooleanArray::class.java
        KeyType.DOUBLE_ARRAY -> DoubleArray::class.java
        KeyType.STRING -> String::class.java
        KeyType.RATIONAL -> Rational::class.java
        KeyType.SIZE -> Size::class.java
        KeyType.RANGE_INT, KeyType.RANGE_LONG, KeyType.RANGE_FLOAT -> Range::class.java
        KeyType.RECT -> Rect::class.java
        KeyType.POINT -> Point::class.java
        KeyType.METERING_RECTANGLES -> Array<MeteringRectangle>::class.java
        KeyType.RGGB_CHANNEL_VECTOR -> RggbChannelVector::class.java
        KeyType.COLOR_SPACE_TRANSFORM -> ColorSpaceTransform::class.java
        KeyType.TONEMAP_CURVE -> TonemapCurve::class.java
    }

    /** Guess the editor type from a live value (from a CaptureResult or a template builder). */
    fun inferType(value: Any?): KeyType? = when (value) {
        null -> null
        is Int -> KeyType.INT
        is Long -> KeyType.LONG
        is Float -> KeyType.FLOAT
        is Double -> KeyType.DOUBLE
        is Boolean -> KeyType.BOOLEAN
        is Byte -> KeyType.BYTE
        is IntArray -> KeyType.INT_ARRAY
        is FloatArray -> KeyType.FLOAT_ARRAY
        is LongArray -> KeyType.LONG_ARRAY
        is ByteArray -> KeyType.BYTE_ARRAY
        is BooleanArray -> KeyType.BOOLEAN_ARRAY
        is DoubleArray -> KeyType.DOUBLE_ARRAY
        is String -> KeyType.STRING
        is Rational -> KeyType.RATIONAL
        is Size -> KeyType.SIZE
        is Range<*> -> when (value.lower) { is Int -> KeyType.RANGE_INT; is Long -> KeyType.RANGE_LONG; is Float -> KeyType.RANGE_FLOAT; else -> null }
        is Rect -> KeyType.RECT
        is Point -> KeyType.POINT
        is Array<*> -> if (value.isArrayOf<MeteringRectangle>()) KeyType.METERING_RECTANGLES else null
        is RggbChannelVector -> KeyType.RGGB_CHANNEL_VECTOR
        is ColorSpaceTransform -> KeyType.COLOR_SPACE_TRANSFORM
        is TonemapCurve -> KeyType.TONEMAP_CURVE
        else -> null
    }

    private fun nums(text: String): List<String> = text.split(',', ' ', ';', '\n', '\t', '[', ']', '(', ')').map { it.trim() }.filter { it.isNotEmpty() }

    private fun rational(s: String): Rational {
        val t = s.trim()
        return if (t.contains('/')) {
            val (n, d) = t.split('/'); Rational(n.trim().toInt(), d.trim().toInt())
        } else {
            val f = t.toDouble(); Rational((f * 10000).roundToInt(), 10000)
        }
    }

    @Throws(IllegalArgumentException::class)
    fun parse(type: KeyType, text: String): Any {
        val t = text.trim()
        try {
            return when (type) {
                KeyType.INT -> t.toInt()
                KeyType.LONG -> t.toLong()
                KeyType.FLOAT -> t.toFloat()
                KeyType.DOUBLE -> t.toDouble()
                KeyType.BOOLEAN -> when (t.lowercase()) { "1", "true", "on", "yes" -> true; "0", "false", "off", "no" -> false; else -> throw IllegalArgumentException("boolean expected") }
                KeyType.BYTE -> t.toInt().toByte()
                KeyType.INT_ARRAY -> nums(t).map { it.toInt() }.toIntArray()
                KeyType.FLOAT_ARRAY -> nums(t).map { it.toFloat() }.toFloatArray()
                KeyType.LONG_ARRAY -> nums(t).map { it.toLong() }.toLongArray()
                KeyType.BYTE_ARRAY -> nums(t).map { it.toInt().toByte() }.toByteArray()
                KeyType.BOOLEAN_ARRAY -> nums(t).map { parse(KeyType.BOOLEAN, it) as Boolean }.toBooleanArray()
                KeyType.DOUBLE_ARRAY -> nums(t).map { it.toDouble() }.toDoubleArray()
                KeyType.STRING -> t
                KeyType.RATIONAL -> rational(t)
                KeyType.SIZE -> { val p = t.lowercase().split('x', '×', ','); Size(p[0].trim().toInt(), p[1].trim().toInt()) }
                KeyType.RANGE_INT -> { val p = nums(t.replace('-', ',').replace("..", ",")); Range(p[0].toInt(), p[1].toInt()) }
                KeyType.RANGE_LONG -> { val p = nums(t.replace("..", ",")); Range(p[0].toLong(), p[1].toLong()) }
                KeyType.RANGE_FLOAT -> { val p = nums(t.replace("..", ",")); Range(p[0].toFloat(), p[1].toFloat()) }
                KeyType.RECT -> { val p = nums(t).map { it.toInt() }; Rect(p[0], p[1], p[2], p[3]) }
                KeyType.POINT -> { val p = nums(t).map { it.toInt() }; Point(p[0], p[1]) }
                KeyType.METERING_RECTANGLES -> t.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { r ->
                    val p = nums(r).map { it.toInt() }
                    MeteringRectangle(p[0], p[1], p[2], p[3], p.getOrElse(4) { MeteringRectangle.METERING_WEIGHT_MAX })
                }.toTypedArray()
                KeyType.RGGB_CHANNEL_VECTOR -> { val p = nums(t).map { it.toFloat() }; RggbChannelVector(p[0], p[1], p[2], p[3]) }
                KeyType.COLOR_SPACE_TRANSFORM -> { val p = nums(t).map { rational(it) }; require(p.size == 9) { "9 elements expected" }; ColorSpaceTransform(p.toTypedArray()) }
                KeyType.TONEMAP_CURVE -> parseTonemap(t)
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Cannot parse '$text' as ${type.label}: ${e.message}")
        }
    }

    /** "r: 0,0,1,1; g: ...; b: ..." or a single "0,0,0.5,0.7,1,1" applied to all channels. */
    private fun parseTonemap(t: String): TonemapCurve {
        val channels = HashMap<Char, FloatArray>()
        val parts = t.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size >= 3 && parts.all { it.length > 2 && it[1] == ':' }) {
            parts.forEach { p -> channels[p[0].lowercaseChar()] = nums(p.substring(2)).map { it.toFloat() }.toFloatArray() }
            return TonemapCurve(channels['r']!!, channels['g']!!, channels['b']!!)
        }
        val all = nums(t).map { it.toFloat() }.toFloatArray()
        require(all.size >= 4 && all.size % 2 == 0) { "even number of (in,out) values expected" }
        return TonemapCurve(all, all.copyOf(), all.copyOf())
    }

    fun format(value: Any?): String = when (value) {
        null -> "—"
        is IntArray -> value.joinToString(", ")
        is FloatArray -> value.joinToString(", ") { fmt(it) }
        is LongArray -> value.joinToString(", ")
        is ByteArray -> value.joinToString(", ") { it.toString() }
        is BooleanArray -> value.joinToString(", ")
        is DoubleArray -> value.joinToString(", ")
        is Float -> fmt(value)
        is Size -> "${value.width}x${value.height}"
        is Range<*> -> "[${value.lower}, ${value.upper}]"
        is Rect -> "${value.left}, ${value.top}, ${value.right}, ${value.bottom}"
        is Point -> "${value.x}, ${value.y}"
        is Rational -> "${value.numerator}/${value.denominator}"
        is RggbChannelVector -> "${fmt(value.red)}, ${fmt(value.greenEven)}, ${fmt(value.greenOdd)}, ${fmt(value.blue)}"
        is ColorSpaceTransform -> (0 until 3).flatMap { r -> (0 until 3).map { c -> value.getElement(c, r) } }.joinToString(", ") { "${it.numerator}/${it.denominator}" }
        is TonemapCurve -> "r: ${curve(value, TonemapCurve.CHANNEL_RED)}; g: ${curve(value, TonemapCurve.CHANNEL_GREEN)}; b: ${curve(value, TonemapCurve.CHANNEL_BLUE)}"
        is Array<*> -> value.joinToString("; ") { e ->
            when (e) {
                is MeteringRectangle -> "${e.x}, ${e.y}, ${e.width}, ${e.height}, ${e.meteringWeight}"
                else -> format(e)
            }
        }
        else -> value.toString()
    }

    private fun curve(c: TonemapCurve, ch: Int): String {
        val n = c.getPointCount(ch)
        val arr = FloatArray(n * 2)
        c.copyColorCurve(ch, arr, 0)
        return arr.joinToString(",") { fmt(it) }
    }

    private fun fmt(f: Float): String = if (f == f.toLong().toFloat()) f.toLong().toString() else String.format("%.4f", f).trimEnd('0').trimEnd('.')
}
