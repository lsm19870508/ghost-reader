package cn.local.ghostreader

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

object BookLoader {
    const val MAX_BYTES = 16L * 1024 * 1024
    data class Book(val key: String, val engine: PageEngine)

    @JvmOverloads
    fun load(path: String, encoding: String, columns: Int, pageLines: Int = 4): Book {
        val file = Path.of(path.trim()).toRealPath()
        require(Files.isRegularFile(file) && Files.isReadable(file)) { "请选择可读取的本地 TXT 文件。" }
        require(Files.size(file) <= MAX_BYTES) { "MVP 支持最大 16 MiB 的 TXT，请先拆分文件。" }
        // Limit the stream as well as checking size, in case the file grows during the read.
        val bytes = Files.newInputStream(file).use { it.readNBytes(MAX_BYTES.toInt() + 1) }
        require(bytes.size <= MAX_BYTES) { "文件超过 16 MiB，请先拆分文件。" }
        val charset = when {
            encoding != "AUTO" -> Charset.forName(encoding)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charset.forName("UTF-16LE")
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charset.forName("UTF-16BE")
            else -> Charsets.UTF_8
        }
        val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = PageEngine.normalize(decoder.decode(ByteBuffer.wrap(bytes)).toString())
        require(text.isNotBlank()) { "TXT 里没有可显示的文字。" }
        return Book(file.toString(), PageEngine(text, columns, pageLines))
    }
}
