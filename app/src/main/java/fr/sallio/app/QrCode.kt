package fr.sallio.app

/**
 * A QR code encoder, small and dependency-free, for the canteen card: byte
 * mode, error correction level M, versions 1 to 10 (up to 213 bytes), best of
 * the 8 masks. After Project Nayuki's reference implementation (MIT), which
 * follows ISO/IEC 18004.
 *
 * [modules] is row-major: modules[y][x], true for dark.
 */
class QrCode private constructor(val version: Int, val modules: Array<BooleanArray>) {

    val size: Int get() = modules.size

    companion object {
        // Level M, versions 1 to 10.
        private val ECC_PER_BLOCK = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val BLOCKS = intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
        private const val FORMAT_M = 0

        fun encode(text: String): QrCode {
            val data = text.toByteArray(Charsets.UTF_8)
            val version = (1..10).firstOrNull { v ->
                4 + (if (v < 10) 8 else 16) + 8 * data.size <= dataCodewords(v) * 8
            } ?: throw IllegalArgumentException("Trop long pour un QR code version 10")

            // Bit stream: mode, count, bytes, terminator, padding.
            val bits = ArrayList<Boolean>()
            fun put(value: Int, length: Int) { for (i in length - 1 downTo 0) bits += (value ushr i) and 1 != 0 }
            put(0b0100, 4)
            put(data.size, if (version < 10) 8 else 16)
            for (b in data) put(b.toInt() and 0xFF, 8)
            val capacity = dataCodewords(version) * 8
            put(0, minOf(4, capacity - bits.size))
            put(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity) { put(pad, 8); pad = pad xor 0xEC xor 0x11 }
            val codewords = ByteArray(bits.size / 8) { i ->
                var v = 0
                for (j in 0 until 8) v = (v shl 1) or (if (bits[i * 8 + j]) 1 else 0)
                v.toByte()
            }
            return build(version, interleave(version, codewords))
        }

        private fun rawModules(v: Int): Int {
            var result = (16 * v + 128) * v + 64
            if (v >= 2) {
                val align = v / 7 + 2
                result -= (25 * align - 10) * align - 55
                if (v >= 7) result -= 36
            }
            return result
        }

        private fun dataCodewords(v: Int) = rawModules(v) / 8 - ECC_PER_BLOCK[v - 1] * BLOCKS[v - 1]

        // ---- Reed-Solomon over GF(256), polynomial 0x11D -------------------------------

        private fun multiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private fun divisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in result.indices) {
                    result[j] = multiply(result[j], root)
                    if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
                }
                root = multiply(root, 0x02)
            }
            return result
        }

        private fun remainder(data: IntArray, divisor: IntArray): IntArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = b xor result[0]
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor multiply(divisor[i], factor)
            }
            return result
        }

        /** Splits the data in blocks, adds each block's error correction, interleaves. */
        private fun interleave(v: Int, data: ByteArray): IntArray {
            val blocks = BLOCKS[v - 1]
            val eccLen = ECC_PER_BLOCK[v - 1]
            val raw = rawModules(v) / 8
            val shortBlocks = blocks - raw % blocks
            val shortLen = raw / blocks
            val div = divisor(eccLen)
            val all = ArrayList<IntArray>()
            var k = 0
            for (i in 0 until blocks) {
                val len = shortLen - eccLen + (if (i < shortBlocks) 0 else 1)
                val dat = IntArray(len) { data[k + it].toInt() and 0xFF }
                k += len
                val ecc = remainder(dat, div)
                // Short blocks get a placeholder so every block has the same length.
                val block = IntArray(shortLen + 1)
                for (j in dat.indices) block[j] = dat[j]
                val start = if (i < shortBlocks) dat.size + 1 else dat.size
                for (j in ecc.indices) block[start + j] = ecc[j]
                all += block
            }
            val result = IntArray(raw)
            var n = 0
            for (i in 0..shortLen) for (j in 0 until blocks) {
                if (i != shortLen - eccLen || j >= shortBlocks) result[n++] = all[j][i]
            }
            return result
        }

        // ---- Layout --------------------------------------------------------------------

        private fun build(v: Int, codewords: IntArray): QrCode {
            val size = v * 4 + 17
            val m = Array(size) { BooleanArray(size) }
            val fn = Array(size) { BooleanArray(size) }
            fun set(x: Int, y: Int, dark: Boolean) { m[y][x] = dark; fn[y][x] = true }

            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            fun finder(cx: Int, cy: Int) {
                for (dy in -4..4) for (dx in -4..4) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until size && y in 0 until size) {
                        val d = maxOf(Math.abs(dx), Math.abs(dy))
                        set(x, y, d != 2 && d != 4)
                    }
                }
            }
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            if (v >= 2) {
                val count = v / 7 + 2
                val step = (v * 8 + count * 3 + 5) / (count * 4 - 4) * 2
                val pos = IntArray(count)
                pos[0] = 6
                var p = size - 7
                for (i in count - 1 downTo 1) { pos[i] = p; p -= step }
                for (i in 0 until count) for (j in 0 until count) {
                    if ((i == 0 && j == 0) || (i == 0 && j == count - 1) || (i == count - 1 && j == 0)) continue
                    for (dy in -2..2) for (dx in -2..2) set(pos[i] + dx, pos[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
                }
            }
            fun format(mask: Int) {
                val data = FORMAT_M shl 3 or mask
                var rem = data
                repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
                val bits = (data shl 10 or rem) xor 0x5412
                fun bit(i: Int) = (bits ushr i) and 1 != 0
                for (i in 0..5) set(8, i, bit(i))
                set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
                for (i in 9 until 15) set(14 - i, 8, bit(i))
                for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
                for (i in 8 until 15) set(8, size - 15 + i, bit(i))
                set(8, size - 8, true)
            }
            format(0)
            if (v >= 7) {
                var rem = v
                repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
                val bits = v shl 12 or rem
                for (i in 0 until 18) {
                    val dark = (bits ushr i) and 1 != 0
                    val a = size - 11 + i % 3
                    val b = i / 3
                    set(a, b, dark); set(b, a, dark)
                }
            }

            // Codewords in the zigzag, two columns at a time, skipping the timing column.
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = (right + 1) and 2 == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!fn[y][x] && i < codewords.size * 8) {
                        m[y][x] = (codewords[i ushr 3] ushr (7 - (i and 7))) and 1 != 0
                        i++
                    }
                }
                right -= 2
            }

            fun applyMask(mask: Int) {
                for (y in 0 until size) for (x in 0 until size) {
                    if (fn[y][x]) continue
                    val invert = when (mask) {
                        0 -> (x + y) % 2 == 0
                        1 -> y % 2 == 0
                        2 -> x % 3 == 0
                        3 -> (x + y) % 3 == 0
                        4 -> (x / 3 + y / 2) % 2 == 0
                        5 -> x * y % 2 + x * y % 3 == 0
                        6 -> (x * y % 2 + x * y % 3) % 2 == 0
                        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    }
                    if (invert) m[y][x] = !m[y][x]
                }
            }
            var best = 0
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0 until 8) {
                applyMask(mask)
                format(mask)
                val p = penalty(m)
                if (p < bestPenalty) { bestPenalty = p; best = mask }
                applyMask(mask)
            }
            applyMask(best)
            format(best)
            return QrCode(v, m)
        }

        /** ISO 18004's penalty: long runs, 2x2 blocks, finder look-alikes, dark/light balance. */
        private fun penalty(m: Array<BooleanArray>): Int {
            val size = m.size
            var result = 0
            fun line(get: (Int) -> Boolean) {
                var run = 1
                for (i in 1 until size) {
                    if (get(i) == get(i - 1)) {
                        run++
                        if (run == 5) result += 3 else if (run > 5) result++
                    } else run = 1
                }
                val a = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
                for (i in 0..size - a.size) {
                    if (a.indices.all { get(i + it) == a[it] }) result += 40
                    if (a.indices.all { get(i + it) == a[a.size - 1 - it] }) result += 40
                }
            }
            for (y in 0 until size) line { m[y][it] }
            for (x in 0 until size) line { m[it][x] }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = m[y][x]
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) result += 3
            }
            val dark = m.sumOf { row -> row.count { it } }
            val total = size * size
            val k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1
            return result + k * 10
        }
    }
}
