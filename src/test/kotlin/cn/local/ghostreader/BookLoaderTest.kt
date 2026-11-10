package cn.local.ghostreader

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset

class BookLoaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `UTF-8 and BOM UTF-16 load without altering source bytes`() {
        val text = "清晨的第一缕光\r\n落在旧书的封面上。"
        for (encoding in listOf("UTF-8", "UTF-16")) {
            val file = temporary.newFile("book-$encoding.txt")
            val bytes = text.toByteArray(Charset.forName(encoding))
            file.writeBytes(bytes)
            val loaded = BookLoader.load(file.absolutePath, "AUTO", 72)
            assertEquals(text.replace("\r\n", "\n"), loaded.engine.text)
            assertEquals(file.toPath().toRealPath().toString(), loaded.key)
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun `GB18030 supports Chinese novels when explicitly selected`() {
        val file = temporary.newFile("gbk.txt")
        file.writeBytes("春风又绿江南岸".toByteArray(Charset.forName("GB18030")))
        assertEquals("春风又绿江南岸", BookLoader.load(file.absolutePath, "GB18030", 72).engine.text)
    }

    @Test(expected = CharacterCodingException::class) fun `wrong encoding produces an error instead of mojibake`() {
        val file = temporary.newFile("invalid.txt")
        file.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        BookLoader.load(file.absolutePath, "UTF-8", 72)
    }

    @Test(expected = IllegalArgumentException::class) fun `empty novel is rejected`() {
        val file = temporary.newFile("empty.txt")
        file.writeText("\n  \n")
        BookLoader.load(file.absolutePath, "AUTO", 72)
    }

    @Test(expected = IllegalArgumentException::class) fun `oversized novel is rejected before reading`() {
        val file = temporary.newFile("large.txt")
        RandomAccessFile(file, "rw").use { it.setLength(BookLoader.MAX_BYTES + 1) }
        BookLoader.load(file.absolutePath, "AUTO", 72)
    }
}
