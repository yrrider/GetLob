package com.yrrider.getlob.data

import java.security.SecureRandom
import java.util.UUID

object UuidV7 {
    private val random = SecureRandom()

    fun generate(): String = next()

    fun next(): String {
        val now = System.currentTimeMillis()
        val randomBytes = ByteArray(10)
        random.nextBytes(randomBytes)

        // 48-bit timestamp
        val timeHigh = ((now ushr 16) and 0xFFFFFFFFL)
        val timeMid = (now and 0xFFFFL)

        // 4-bit version (7) + 12-bit random
        val rand12 = ((randomBytes[0].toInt() and 0x0F) shl 8) or (randomBytes[1].toInt() and 0xFF)
        val timeHiAndVersion = (0x7000L or rand12.toLong())

        // 2-bit variant (0b10) + 62-bit random
        val rand62High = ((randomBytes[2].toInt() and 0x3F) or 0x80).toLong()
        var lsb = rand62High shl 56
        for (i in 3 until 10) {
            lsb = lsb or ((randomBytes[i].toLong() and 0xFFL) shl ((9 - i) * 8))
        }

        val msb = (timeHigh shl 32) or (timeMid shl 16) or timeHiAndVersion
        return UUID(msb, lsb).toString()
    }
}
