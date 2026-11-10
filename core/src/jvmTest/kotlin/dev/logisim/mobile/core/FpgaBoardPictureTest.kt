package dev.logisim.mobile.core

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FpgaBoardPictureTest {
    @Test
    fun decodesTheEmbeddedBasys3Jpeg() {
        val xml = assertNotNull(javaClass.getResourceAsStream("/fpga/BASYS3.xml"))
            .bufferedReader().use { it.readText() }
        val bytes = assertNotNull(FpgaBoardPicture.read(xml)).imageBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("95d2fc0484e5330d2aaed925ffb4ff85a03ef3472df11ff2fa7b6a26ec9de426", digest)
        val image = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        assertEquals(740, image.width)
        assertEquals(400, image.height)
    }

    @Test
    fun decodesLegacyRgbAndRejectsAnIncompletePixel() {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789()"
        val table = listOf("", "+", "-", "=").flatMap { prefix -> alphabet.map { prefix + it } }
        fun picture(stream: String) = assertNotNull(FpgaBoardPicture.read("""
            <Board>
              <BoardPicture>
                <PictureDimension Width="1" Height="1"/>
                <CompressionCodeTable TableData="${table.joinToString(" ")}"/>
                <PixelData PixelRGB="$stream"/>
              </BoardPicture>
            </Board>
        """.trimIndent()))
        val image = assertNotNull(ImageIO.read(ByteArrayInputStream(picture("=)b+c").imageBytes())))
        assertEquals(0xffff0142.toInt(), image.getRGB(0, 0))
        assertFailsWith<IllegalArgumentException> { picture("=)b+").imageBytes() }
        assertNull(FpgaBoardPicture.read("<Board/>"))
    }
}
