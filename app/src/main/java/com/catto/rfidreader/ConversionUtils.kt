package com.catto.rfidreader

import java.math.BigInteger

fun bytesToHexString(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    return bytes.joinToString(" ") { "%02X".format(it) }
}

fun bytesToDecString(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    return BigInteger(1, bytes).toString()
}

fun bytesToBinString(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    return bytes.joinToString(" ") { byte ->
        String.format("%8s", Integer.toBinaryString(byte.toInt() and 0xFF)).replace(' ', '0')
    }
}
