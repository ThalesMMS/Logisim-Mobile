package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message

fun MemoryImageFormat.localizedDescription(): LocalizedText = message(when (this) {
    MemoryImageFormat.RAW -> Messages.MemoryFormatRaw
    MemoryImageFormat.HEX_WORDS_PLAIN -> Messages.MemoryFormatHexWordsPlain
    MemoryImageFormat.HEX_WORDS_ADDRESSED -> Messages.MemoryFormatHexWordsAddressed
    MemoryImageFormat.HEX_BYTES_PLAIN_BIG -> Messages.MemoryFormatHexBytesPlainBig
    MemoryImageFormat.HEX_BYTES_PLAIN_LITTLE -> Messages.MemoryFormatHexBytesPlainLittle
    MemoryImageFormat.HEX_BYTES_ADDRESSED_BIG -> Messages.MemoryFormatHexBytesAddressedBig
    MemoryImageFormat.HEX_BYTES_ADDRESSED_LITTLE -> Messages.MemoryFormatHexBytesAddressedLittle
    MemoryImageFormat.BINARY_BIG -> Messages.MemoryFormatBinaryBig
    MemoryImageFormat.BINARY_LITTLE -> Messages.MemoryFormatBinaryLittle
    MemoryImageFormat.ASCII_BIG -> Messages.MemoryFormatAsciiBig
    MemoryImageFormat.ASCII_LITTLE -> Messages.MemoryFormatAsciiLittle
})
