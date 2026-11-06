package local.tvhome

/** Makes QR codes (byte mode, medium error correction, up to version 10), with no libraries. */
class QrCode private constructor(
    val size: Int,
    private val dark: BooleanArray,
) {
    fun isDark(
        x: Int,
        y: Int,
    ) = dark[y * size + x]

    private class Builder(
        val version: Int,
    ) {
        val size = version * 4 + 17
        val modules = BooleanArray(size * size)
        val reserved = BooleanArray(size * size)

        fun get(
            x: Int,
            y: Int,
        ) = modules[y * size + x]

        fun set(
            x: Int,
            y: Int,
            value: Boolean,
        ) {
            modules[y * size + x] = value
            reserved[y * size + x] = true
        }

        fun drawPatterns() {
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            drawFinder(3, 3)
            drawFinder(size - 4, 3)
            drawFinder(3, size - 4)
            val centres = alignmentCentres()
            for (a in centres.indices) {
                for (b in centres.indices) {
                    val inFinderCorner = (a == 0 && b == 0) || (a == 0 && b == centres.size - 1) || (a == centres.size - 1 && b == 0)
                    if (!inFinderCorner) drawAlignment(centres[a], centres[b])
                }
            }
            drawFormat(0)
            drawVersion()
        }

        private fun drawFinder(
            x: Int,
            y: Int,
        ) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val distance = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    if (x + dx in 0 until size && y + dy in 0 until size) set(x + dx, y + dy, distance != 2 && distance != 4)
                }
            }
        }

        private fun drawAlignment(
            x: Int,
            y: Int,
        ) {
            for (dy in -2..2) {
                for (dx in -2..2) set(x + dx, y + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
            }
        }

        private fun alignmentCentres(): List<Int> {
            if (version == 1) return emptyList()
            val count = version / 7 + 2
            val step = (version * 8 + count * 3 + 5) / (count * 4 - 4) * 2
            val centres = IntArray(count)
            centres[0] = 6
            var position = size - 7
            for (i in count - 1 downTo 1) {
                centres[i] = position
                position -= step
            }
            return centres.toList()
        }

        fun drawFormat(mask: Int) {
            val data = mask // Medium error correction is 00 in the top bits
            var remainder = data
            repeat(10) { remainder = (remainder shl 1) xor ((remainder ushr 9) * 0x537) }
            val bits = ((data shl 10) or remainder) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6))
            set(8, 8, bit(7))
            set(7, 8, bit(8))
            for (i in 9..14) set(14 - i, 8, bit(i))
            for (i in 0..7) set(size - 1 - i, 8, bit(i))
            for (i in 8..14) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        private fun drawVersion() {
            if (version < 7) return
            var remainder = version
            repeat(12) { remainder = (remainder shl 1) xor ((remainder ushr 11) * 0x1F25) }
            val bits = (version shl 12) or remainder
            for (i in 0..17) {
                val value = (bits ushr i) and 1 != 0
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, value)
                set(b, a, value)
            }
        }

        fun drawData(codewords: IntArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vertical in 0 until size) {
                    for (j in 0..1) {
                        val x = right - j
                        val upward = (right + 1) and 2 == 0
                        val y = if (upward) size - 1 - vertical else vertical
                        if (!reserved[y * size + x] && i < codewords.size * 8) {
                            modules[y * size + x] = (codewords[i ushr 3] ushr (7 - (i and 7))) and 1 != 0
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val flip =
                        when (mask) {
                            0 -> (x + y) % 2 == 0
                            1 -> y % 2 == 0
                            2 -> x % 3 == 0
                            3 -> (x + y) % 3 == 0
                            4 -> (x / 3 + y / 2) % 2 == 0
                            5 -> x * y % 2 + x * y % 3 == 0
                            6 -> (x * y % 2 + x * y % 3) % 2 == 0
                            else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                        }
                    if (flip && !reserved[y * size + x]) modules[y * size + x] = !modules[y * size + x]
                }
            }
        }

        // Lower is easier for a camera to read
        fun penalty(): Int {
            var score = 0
            fun lines(cell: (Int, Int) -> Boolean) {
                for (a in 0 until size) {
                    var run = 1
                    for (b in 1 until size) {
                        if (cell(a, b) == cell(a, b - 1)) {
                            run++
                            if (run == 5) score += 3 else if (run > 5) score++
                        } else {
                            run = 1
                        }
                        if (b >= 10) {
                            val window = (0..10).map { cell(a, b - 10 + it) }
                            if (window == FINDER_LIKE || window == FINDER_LIKE.reversed()) score += 40
                        }
                    }
                }
            }
            lines { a, b -> get(b, a) }
            lines { a, b -> get(a, b) }
            for (y in 0 until size - 1) {
                for (x in 0 until size - 1) {
                    val c = get(x, y)
                    if (c == get(x + 1, y) && c == get(x, y + 1) && c == get(x + 1, y + 1)) score += 3
                }
            }
            val share = modules.count { it } * 100 / modules.size
            return score + kotlin.math.abs(share - 50) / 5 * 10
        }
    }

    companion object {
        private val ECC_PER_BLOCK = intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val BLOCKS = intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
        private const val MAX_VERSION = 10
        private val FINDER_LIKE = listOf(true, false, true, true, true, false, true, false, false, false, false)

        private fun rawCodewords(version: Int): Int {
            var modules = (16 * version + 128) * version + 64
            if (version >= 2) {
                val count = version / 7 + 2
                modules -= (25 * count - 10) * count - 55
                if (version >= 7) modules -= 36
            }
            return modules / 8
        }

        private fun dataCodewords(version: Int) = rawCodewords(version) - ECC_PER_BLOCK[version] * BLOCKS[version]

        fun encode(text: String): QrCode? {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val version =
                (1..MAX_VERSION).firstOrNull { v ->
                    4 + (if (v < 10) 8 else 16) + bytes.size * 8 <= dataCodewords(v) * 8
                } ?: return null
            val data = dataBytes(bytes, version)
            val code = Builder(version)
            code.drawPatterns()
            code.drawData(withErrorCorrection(data, version))

            var best = 0
            var bestScore = Int.MAX_VALUE
            for (mask in 0..7) {
                code.applyMask(mask)
                code.drawFormat(mask)
                val score = code.penalty()
                if (score < bestScore) {
                    best = mask
                    bestScore = score
                }
                code.applyMask(mask) // Masks undo themselves
            }
            code.applyMask(best)
            code.drawFormat(best)
            return QrCode(code.size, code.modules)
        }

        private fun dataBytes(
            bytes: ByteArray,
            version: Int,
        ): IntArray {
            val bits = ArrayList<Boolean>()
            fun add(
                value: Int,
                length: Int,
            ) {
                for (i in length - 1 downTo 0) bits.add((value ushr i) and 1 != 0)
            }
            add(0b0100, 4) // Byte mode
            add(bytes.size, if (version < 10) 8 else 16)
            bytes.forEach { add(it.toInt() and 0xFF, 8) }
            val capacity = dataCodewords(version) * 8
            add(0, minOf(4, capacity - bits.size))
            add(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity) {
                add(pad, 8)
                pad = pad xor (0xEC xor 0x11)
            }
            return IntArray(bits.size / 8) { i -> (0 until 8).fold(0) { byte, j -> (byte shl 1) or (if (bits[i * 8 + j]) 1 else 0) } }
        }

        // Splits the data into blocks, adds Reed-Solomon codes to each, and interleaves them
        private fun withErrorCorrection(
            data: IntArray,
            version: Int,
        ): IntArray {
            val blocks = BLOCKS[version]
            val ecc = ECC_PER_BLOCK[version]
            val raw = rawCodewords(version)
            val shortBlocks = blocks - raw % blocks
            val shortLength = raw / blocks - ecc
            val divisor = divisor(ecc)
            val dataBlocks = ArrayList<IntArray>()
            val eccBlocks = ArrayList<IntArray>()
            var at = 0
            for (i in 0 until blocks) {
                val length = shortLength + if (i < shortBlocks) 0 else 1
                val block = data.copyOfRange(at, at + length)
                at += length
                dataBlocks.add(block)
                eccBlocks.add(remainder(block, divisor))
            }
            val out = ArrayList<Int>(raw)
            for (i in 0..shortLength) dataBlocks.forEach { if (i < it.size) out.add(it[i]) }
            for (i in 0 until ecc) eccBlocks.forEach { out.add(it[i]) }
            return out.toIntArray()
        }

        private fun multiply(
            a: Int,
            b: Int,
        ): Int {
            var result = 0
            for (i in 7 downTo 0) {
                result = (result shl 1) xor ((result ushr 7) * 0x11D)
                result = result xor (((b ushr i) and 1) * a)
            }
            return result
        }

        private fun divisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in 0 until degree) {
                    result[j] = multiply(result[j], root)
                    if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
                }
                root = multiply(root, 2)
            }
            return result
        }

        private fun remainder(
            data: IntArray,
            divisor: IntArray,
        ): IntArray {
            val result = IntArray(divisor.size)
            for (byte in data) {
                val factor = byte xor result[0]
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor multiply(divisor[i], factor)
            }
            return result
        }
    }
}
