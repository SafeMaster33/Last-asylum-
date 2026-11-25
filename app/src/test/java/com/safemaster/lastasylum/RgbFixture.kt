package com.safemaster.lastasylum

import java.io.DataInputStream
import java.util.zip.GZIPInputStream

internal class RgbFixture(val width: Int, val height: Int, val pixels: IntArray) {
    companion object {
        /** Two big-endian 32-bit dimensions followed by packed row-major RGB. */
        fun load(name: String): RgbFixture {
            val resource = checkNotNull(RgbFixture::class.java.getResourceAsStream("/$name.rgb.gz"))
            DataInputStream(GZIPInputStream(resource).buffered()).use { stream ->
                val width = stream.readInt()
                val height = stream.readInt()
                val pixels = IntArray(width * height) {
                    val r = stream.readUnsignedByte()
                    val g = stream.readUnsignedByte()
                    val b = stream.readUnsignedByte()
                    (0xff shl 24) or (r shl 16) or (g shl 8) or b
                }
                check(stream.read() == -1)
                return RgbFixture(width, height, pixels)
            }
        }
    }
}
