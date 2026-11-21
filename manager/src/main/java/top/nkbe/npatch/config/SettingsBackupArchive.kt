package top.nkbe.npatch.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.Strictness
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest

internal enum class BackupCompression(val level: CompressionLevel?) {
    STORE(null),
    FAST(CompressionLevel.FASTEST),
    STANDARD(CompressionLevel.NORMAL),
    MAXIMUM(CompressionLevel.ULTRA),
}

internal class BackupFormatException : IOException()
internal class BackupSizeException : IOException()
internal class BackupVersionException : IOException()
internal class BackupPasswordRequired : IOException()

internal object SettingsBackupArchive {
    const val JSON = "settings.json"
    const val BACKGROUND = "background"
    const val KEYSTORE = "keystore.bks"
    const val MAX_BYTES = 64L * 1024 * 1024
    private const val FORMAT = "npatch-settings"
    private const val VERSION = 1
    private val entryNames = setOf(JSON, BACKGROUND, KEYSTORE)
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()
    
    data class Contents(
        val settings: JsonObject,
        val assets: Map<String, ByteArray>,
    )
    
    fun write(file: File, settings: JsonObject, assets: Map<String, ByteArray>, compression: BackupCompression, password: CharArray?) {
        val document = JsonObject().apply {
            addProperty("format", FORMAT)
            addProperty("version", VERSION)
            add("settings", settings)
            add("assets", JsonObject().apply {
                assets.forEach { (name, bytes) -> addProperty(name, sha256(bytes)) }
            })
        }
        val json = gson.toJson(document).encodeToByteArray()
        if (json.size + assets.values.sumOf { it.size.toLong() } > MAX_BYTES) throw BackupSizeException()
        
        val zip = if (password == null) ZipFile(file) else ZipFile(file, password)
        zip.use { archive ->
            for ((name, bytes) in mapOf(JSON to json) + assets) {
                val parameters = ZipParameters().apply {
                    fileNameInZip = name
                    entrySize = bytes.size.toLong()
                    if (compression.level == null) {
                        compressionMethod = CompressionMethod.STORE
                    } else {
                        compressionMethod = CompressionMethod.DEFLATE
                        compressionLevel = compression.level
                    }
                    if (password != null) {
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }
                }
                bytes.inputStream().use { archive.addStream(it, parameters) }
            }
        }
        if (file.length() > MAX_BYTES) throw BackupSizeException()
    }
    
    fun read(file: File, password: CharArray?): Contents {
        if (file.length() > MAX_BYTES) throw BackupSizeException()
        val zip = if (password == null) ZipFile(file) else ZipFile(file, password)
        try {
            return zip.use { archive ->
                val headers = archive.fileHeaders
                val entries = headers.associateBy { it.fileName }
                if (archive.isSplitArchive || entries.size != headers.size ||
                    JSON !in entries || !entryNames.containsAll(entries.keys) || headers.any { it.isDirectory }
                ) throw BackupFormatException()
                
                val encrypted = headers.any { it.isEncrypted }
                if (encrypted && headers.any {
                        it.encryptionMethod != EncryptionMethod.AES ||
                            it.aesExtraDataRecord?.aesKeyStrength != AesKeyStrength.KEY_STRENGTH_256
                    }
                ) throw BackupFormatException()
                if (encrypted && password == null) throw BackupPasswordRequired()
                
                var remaining = MAX_BYTES
                fun readEntry(name: String): ByteArray {
                    val header = entries.getValue(name)
                    if (header.uncompressedSize !in 0..remaining) throw BackupSizeException()
                    return archive.getInputStream(header).use { it.readBounded(remaining) }.also {
                        remaining -= it.size
                        if (header.uncompressedSize != it.size.toLong()) throw BackupFormatException()
                    }
                }
                
                val document = parseObject(readEntry(JSON))
                if (document.string("format") != FORMAT) throw BackupFormatException()
                if (document.integer("version") != VERSION) throw BackupVersionException()
                val hashes = document.obj("assets")
                if (hashes.keySet() != entries.keys - JSON) throw BackupFormatException()
                val assets = hashes.keySet().associateWith { name ->
                    readEntry(name).also { if (sha256(it) != hashes.string(name)) throw BackupFormatException() }
                }
                Contents(document.obj("settings"), assets)
            }
        } catch (exception: ZipException) {
            throw BackupFormatException().apply { initCause(exception) }
        }
    }
    
    fun parseObject(bytes: ByteArray): JsonObject =
        try {
            gson.fromJson(bytes.decodeToString(throwOnInvalidSequence = true), JsonObject::class.java)
                ?: throw BackupFormatException()
        } catch (exception: IllegalArgumentException) {
            throw BackupFormatException().apply { initCause(exception) }
        } catch (exception: JsonParseException) {
            throw BackupFormatException().apply { initCause(exception) }
        } catch (exception: CharacterCodingException) {
            throw BackupFormatException().apply { initCause(exception) }
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

internal fun InputStream.copyBounded(output: OutputStream, limit: Long) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = limit
    var count = read(buffer)
    while (count != -1) {
        remaining -= count
        if (remaining < 0) throw BackupSizeException()
        output.write(buffer, 0, count)
        count = read(buffer)
    }
}

internal fun InputStream.readBounded(limit: Long): ByteArray =
    java.io.ByteArrayOutputStream().also { copyBounded(it, limit) }.toByteArray()

internal fun JsonObject.obj(key: String): JsonObject =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: throw BackupFormatException()

internal fun JsonObject.string(key: String): String =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: throw BackupFormatException()

internal fun JsonObject.integer(key: String): Int =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toIntOrNull()
        ?: throw BackupFormatException()
