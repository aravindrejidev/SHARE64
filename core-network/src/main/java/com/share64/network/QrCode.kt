package com.share64.network

/**
 * A QR Code (ISO/IEC 18004) generator, byte mode, versions 1 to 40, all four error-correction
 * levels. Pure Kotlin with no dependencies, so the QR code on screen is made entirely inside the
 * app (no image library, no network). Verified against an independent encoder (identical matrices
 * for every version 1 to 40 and level when given the same mask) and a real decoder. Mask choice
 * follows the standard's four penalty rules; encoders differ slightly there, and every mask is valid.
 *
 * ```
 * val qr = QrCode.encode("share64://c?v=1&...")
 * for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr.isDark(x, y)) draw(x, y)
 * ```
 */
class QrCode private constructor(
    val version: Int,
    val level: Level,
    /** The mask pattern (0 to 7) that scored best. */
    val mask: Int,
    private val modules: BooleanArray,
) {
    /** Error-correction level: how much damage the code survives. */
    enum class Level(internal val formatBits: Int) {
        LOW(1), MEDIUM(0), QUARTILE(3), HIGH(2)
    }

    /** Side length in modules (21 for version 1, +4 per version). */
    val size: Int = version * 4 + 17

    /** True for a dark module. Out-of-range coordinates are light (the quiet zone). */
    fun isDark(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && modules[y * size + x]

    /** True when (x, y) belongs to one of the three big finder squares (with their separators). */
    fun isFinder(x: Int, y: Int): Boolean {
        val near = 8
        val far = size - 8
        return (x < near && y < near) || (x >= far && y < near) || (x < near && y >= far)
    }

    companion object {
        const val MAX_VERSION = 40

        /** The smallest version that fits [text] at [level] (the level is raised for free if it still fits). */
        fun encode(text: String, level: Level = Level.MEDIUM, boostLevel: Boolean = false): QrCode =
            encodeBytes(text.toByteArray(Charsets.UTF_8), level, boostLevel)

        fun encodeBytes(data: ByteArray, level: Level = Level.MEDIUM, boostLevel: Boolean = false): QrCode {
            var version = 1
            var usedBits: Int
            while (true) {
                val capacityBits = numDataCodewords(version, level) * 8
                usedBits = 4 + charCountBits(version) + data.size * 8
                if (usedBits <= capacityBits) break
                if (version >= MAX_VERSION) throw IllegalArgumentException("Data too long for a QR code (${data.size} bytes)")
                version++
            }
            var chosen = level
            if (boostLevel) {
                for (candidate in listOf(Level.MEDIUM, Level.QUARTILE, Level.HIGH)) {
                    if (candidate.ordinal > chosen.ordinal && usedBits <= numDataCodewords(version, candidate) * 8) chosen = candidate
                }
            }

            // ---- data bits: mode (byte = 0100), character count, bytes, terminator, padding
            val bits = ArrayList<Boolean>()
            fun append(value: Int, count: Int) {
                for (i in count - 1 downTo 0) bits.add(((value ushr i) and 1) != 0)
            }
            append(0b0100, 4)
            append(data.size, charCountBits(version))
            for (b in data) append(b.toInt() and 0xFF, 8)
            val capacityBits = numDataCodewords(version, chosen) * 8
            append(0, minOf(4, capacityBits - bits.size))
            append(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacityBits) {
                append(pad, 8)
                pad = pad xor (0xEC xor 0x11)
            }
            val dataCodewords = ByteArray(bits.size / 8)
            for (i in bits.indices) if (bits[i]) dataCodewords[i ushr 3] = (dataCodewords[i ushr 3].toInt() or (1 shl (7 - (i and 7)))).toByte()

            val all = addEccAndInterleave(dataCodewords, version, chosen)
            return build(version, chosen, all)
        }

        // ------------------------------------------------------------------ building the matrix

        private fun build(version: Int, level: Level, codewords: ByteArray): QrCode {
            val size = version * 4 + 17
            val modules = BooleanArray(size * size)
            val isFunction = BooleanArray(size * size)

            fun set(x: Int, y: Int, dark: Boolean) {
                modules[y * size + x] = dark
                isFunction[y * size + x] = true
            }

            // timing patterns
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            // finder patterns (with separators)
            fun finder(cx: Int, cy: Int) {
                for (dy in -4..4) for (dx in -4..4) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until size && y in 0 until size) {
                        val dist = maxOf(Math.abs(dx), Math.abs(dy))
                        set(x, y, dist != 2 && dist != 4)
                    }
                }
            }
            finder(3, 3)
            finder(size - 4, 3)
            finder(3, size - 4)
            // alignment patterns
            val align = alignmentPositions(version)
            for (i in align.indices) for (j in align.indices) {
                val skip = (i == 0 && j == 0) || (i == 0 && j == align.size - 1) || (i == align.size - 1 && j == 0)
                if (skip) continue
                for (dy in -2..2) for (dx in -2..2) set(align[i] + dx, align[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
            }
            // reserve format and version areas (real values are written per mask below)
            drawFormatBits(modules, isFunction, size, level, 0)
            drawVersion(modules, isFunction, size, version)

            // zigzag placement of the codeword bits
            var bitIndex = 0
            val totalBits = codewords.size * 8
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0 until 2) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!isFunction[y * size + x] && bitIndex < totalBits) {
                            modules[y * size + x] = ((codewords[bitIndex ushr 3].toInt() ushr (7 - (bitIndex and 7))) and 1) != 0
                            bitIndex++
                        }
                    }
                }
                right -= 2
            }

            // pick the mask with the lowest penalty
            var bestMask = 0
            var minPenalty = Int.MAX_VALUE
            for (m in 0 until 8) {
                applyMask(modules, isFunction, size, m)
                drawFormatBits(modules, isFunction, size, level, m)
                val penalty = penalty(modules, size)
                if (penalty < minPenalty) {
                    bestMask = m
                    minPenalty = penalty
                }
                applyMask(modules, isFunction, size, m) // masks are their own inverse
            }
            applyMask(modules, isFunction, size, bestMask)
            drawFormatBits(modules, isFunction, size, level, bestMask)
            return QrCode(version, level, bestMask, modules)
        }

        private fun drawFormatBits(modules: BooleanArray, isFunction: BooleanArray, size: Int, level: Level, mask: Int) {
            val data = (level.formatBits shl 3) or mask
            var rem = data
            for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            val bits = ((data shl 10) or rem) xor 0x5412
            fun set(x: Int, y: Int, dark: Boolean) {
                modules[y * size + x] = dark
                isFunction[y * size + x] = true
            }
            fun bit(i: Int) = ((bits ushr i) and 1) != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6))
            set(8, 8, bit(7))
            set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true) // the always-dark module
        }

        private fun drawVersion(modules: BooleanArray, isFunction: BooleanArray, size: Int, version: Int) {
            if (version < 7) return
            var rem = version
            for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val dark = ((bits ushr i) and 1) != 0
                val a = size - 11 + i % 3
                val b = i / 3
                modules[b * size + a] = dark
                isFunction[b * size + a] = true
                modules[a * size + b] = dark
                isFunction[a * size + b] = true
            }
        }

        private fun applyMask(modules: BooleanArray, isFunction: BooleanArray, size: Int, mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
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
                if (invert && !isFunction[y * size + x]) modules[y * size + x] = !modules[y * size + x]
            }
        }

        // ------------------------------------------------------------------ mask penalty (spec rules N1-N4)

        private fun penalty(m: BooleanArray, size: Int): Int {
            var result = 0
            fun dark(x: Int, y: Int) = m[y * size + x]

            // N1 + N3, rows then columns
            for (pass in 0 until 2) {
                for (a in 0 until size) {
                    var runColor = false
                    var runLen = 0
                    val history = IntArray(7)
                    for (b in 0 until size) {
                        val c = if (pass == 0) dark(b, a) else dark(a, b)
                        if (c == runColor) {
                            runLen++
                            if (runLen == 5) result += 3 else if (runLen > 5) result++
                        } else {
                            finderPenaltyAddHistory(runLen, history, size)
                            if (!runColor) result += finderPenaltyCountPatterns(history) * 40
                            runColor = c
                            runLen = 1
                        }
                    }
                    result += finderPenaltyTerminateAndCount(runColor, runLen, history, size) * 40
                }
            }
            // N2: 2x2 blocks of one colour
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = dark(x, y)
                if (c == dark(x + 1, y) && c == dark(x, y + 1) && c == dark(x + 1, y + 1)) result += 3
            }
            // N4: balance of dark and light
            var darkCount = 0
            for (v in m) if (v) darkCount++
            val total = size * size
            val k = (Math.abs(darkCount * 20 - total * 10) + total - 1) / total - 1
            result += k * 10
            return result
        }

        private fun finderPenaltyCountPatterns(h: IntArray): Int {
            val n = h[1]
            val core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n
            return (if (core && h[0] >= n * 4 && h[6] >= n) 1 else 0) + (if (core && h[6] >= n * 4 && h[0] >= n) 1 else 0)
        }

        private fun finderPenaltyTerminateAndCount(runColor: Boolean, runLen: Int, h: IntArray, size: Int): Int {
            var len = runLen
            if (runColor) {
                finderPenaltyAddHistory(len, h, size)
                len = 0
            }
            len += size
            finderPenaltyAddHistory(len, h, size)
            return finderPenaltyCountPatterns(h)
        }

        private fun finderPenaltyAddHistory(runLen: Int, h: IntArray, size: Int) {
            var len = runLen
            if (h[0] == 0) len += size // the light border around the code counts as part of the run
            for (i in 6 downTo 1) h[i] = h[i - 1]
            h[0] = len
        }

        // ------------------------------------------------------------------ error correction

        private fun addEccAndInterleave(data: ByteArray, version: Int, level: Level): ByteArray {
            val numBlocks = NUM_ERROR_CORRECTION_BLOCKS[level.ordinal][version]
            val blockEccLen = ECC_CODEWORDS_PER_BLOCK[level.ordinal][version]
            val rawCodewords = numRawDataModules(version) / 8
            val numShortBlocks = numBlocks - rawCodewords % numBlocks
            val shortBlockLen = rawCodewords / numBlocks

            val blocks = ArrayList<ByteArray>()
            val divisor = reedSolomonDivisor(blockEccLen)
            var k = 0
            for (i in 0 until numBlocks) {
                val datLen = shortBlockLen - blockEccLen + if (i < numShortBlocks) 0 else 1
                val dat = data.copyOfRange(k, k + datLen)
                k += datLen
                val ecc = reedSolomonRemainder(dat, divisor)
                val block = ByteArray(shortBlockLen + 1)
                System.arraycopy(dat, 0, block, 0, dat.size)
                System.arraycopy(ecc, 0, block, block.size - blockEccLen, ecc.size)
                blocks.add(block)
            }
            val result = ByteArray(rawCodewords)
            var r = 0
            for (i in 0..shortBlockLen) {
                for (j in blocks.indices) {
                    // short blocks have no byte at this slot
                    if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                        result[r++] = blocks[j][i]
                    }
                }
            }
            return result
        }

        private fun reedSolomonDivisor(degree: Int): ByteArray {
            val result = ByteArray(degree)
            result[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in result.indices) {
                    result[j] = gfMultiply(result[j].toInt() and 0xFF, root).toByte()
                    if (j + 1 < result.size) result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
                }
                root = gfMultiply(root, 0x02)
            }
            return result
        }

        private fun reedSolomonRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
            val result = ByteArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() xor result[0].toInt()) and 0xFF
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) {
                    result[i] = (result[i].toInt() xor gfMultiply(divisor[i].toInt() and 0xFF, factor)).toByte()
                }
            }
            return result
        }

        /** Product in GF(2^8) with the QR polynomial x^8 + x^4 + x^3 + x^2 + 1. */
        private fun gfMultiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        // ------------------------------------------------------------------ sizes and tables

        private fun charCountBits(version: Int) = if (version <= 9) 8 else 16

        private fun alignmentPositions(version: Int): IntArray {
            if (version == 1) return IntArray(0)
            val num = version / 7 + 2
            val step = if (version == 32) 26 else (version * 4 + num * 2 + 1) / (num * 2 - 2) * 2
            val result = IntArray(num)
            result[0] = 6
            var pos = version * 4 + 10
            for (i in num - 1 downTo 1) {
                result[i] = pos
                pos -= step
            }
            return result
        }

        private fun numRawDataModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val num = version / 7 + 2
                result -= (25 * num - 10) * num - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        private fun numDataCodewords(version: Int, level: Level): Int =
            numRawDataModules(version) / 8 -
                ECC_CODEWORDS_PER_BLOCK[level.ordinal][version] * NUM_ERROR_CORRECTION_BLOCKS[level.ordinal][version]

        // index: level (LOW, MEDIUM, QUARTILE, HIGH), version 0 (unused) to 40
        private val ECC_CODEWORDS_PER_BLOCK = arrayOf(
            intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
            intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        )
        private val NUM_ERROR_CORRECTION_BLOCKS = arrayOf(
            intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
            intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
            intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
            intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
        )
    }
}
