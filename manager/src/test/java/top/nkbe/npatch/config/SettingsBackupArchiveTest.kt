package top.nkbe.npatch.config

import com.google.gson.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class SettingsBackupArchiveTest {
    @get:Rule
    val folder = TemporaryFolder(File("build").apply { mkdirs() })
    
    @Test
    fun roundTripsEachCompressionWithAndWithoutPassword() {
        val settings = JsonObject()
        val image = ByteArray(4096) { (it % 3).toByte() }
        val key = ByteArray(137) { it.toByte() }
        val assets = mapOf(SettingsBackupArchive.BACKGROUND to image, SettingsBackupArchive.KEYSTORE to key)
        
        for (compression in BackupCompression.entries) {
            for (password in listOf(null, "password".toCharArray())) {
                val file = folder.newFolder().resolve("backup.zip")
                SettingsBackupArchive.write(file, settings, assets, compression, password)
                if (password != null) {
                    assertThrows(BackupPasswordRequired::class.java) { SettingsBackupArchive.read(file, null) }
                    assertThrows(BackupFormatException::class.java) {
                        SettingsBackupArchive.read(file, "incorrect".toCharArray())
                    }
                } else {
                    ZipFile(file).use { zip ->
                        val method = if (compression == BackupCompression.STORE) ZipEntry.STORED else ZipEntry.DEFLATED
                        assertEquals(method, zip.getEntry(SettingsBackupArchive.JSON).method)
                    }
                }
                val restored = SettingsBackupArchive.read(file, password)
                assertEquals(settings, restored.settings)
                assertArrayEquals(image, restored.assets[SettingsBackupArchive.BACKGROUND])
                assertArrayEquals(key, restored.assets[SettingsBackupArchive.KEYSTORE])
            }
        }
    }
    
    @Test
    fun rejectsArchivesWithUnsupportedVersionsOrUnexpectedEntries() {
        val unsupported = writeZip("""{"format":"npatch-settings","version":2,"settings":{},"assets":{}}""")
        assertThrows(BackupVersionException::class.java) { SettingsBackupArchive.read(unsupported, null) }
        
        val unexpected = writeZip(
            """{"format":"npatch-settings","version":1,"settings":{},"assets":{}}""", "../background",
        )
        assertThrows(BackupFormatException::class.java) { SettingsBackupArchive.read(unexpected, null) }
    }
    
    @Test
    fun rejectsMissingOrChangedAttachments() {
        val manifest = """{"format":"npatch-settings","version":1,"settings":{},"assets":{"background":"invalid"}}"""
        val missing = writeZip(manifest)
        val changed = writeZip(manifest, SettingsBackupArchive.BACKGROUND)
        assertThrows(BackupFormatException::class.java) { SettingsBackupArchive.read(missing, null) }
        assertThrows(BackupFormatException::class.java) { SettingsBackupArchive.read(changed, null) }
    }
    
    @Test
    fun rejectsDuplicateEntriesAndOversizedInput() {
        val manifest = """{"format":"npatch-settings","version":1,"settings":{},"assets":{}}"""
        val duplicate = writeZip(manifest, "settingx.json")
        // ZipOutputStream rejects duplicate names, so rename the second entry in both headers.
        val encoded = duplicate.readBytes().toString(Charsets.ISO_8859_1)
        duplicate.writeBytes(encoded.replace("settingx.json", "settings.json").toByteArray(Charsets.ISO_8859_1))
        assertThrows(BackupFormatException::class.java) { SettingsBackupArchive.read(duplicate, null) }
        
        val oversized = folder.newFile()
        RandomAccessFile(oversized, "rw").use { it.setLength(SettingsBackupArchive.MAX_BYTES + 1) }
        assertThrows(BackupSizeException::class.java) { SettingsBackupArchive.read(oversized, null) }
        assertThrows(BackupSizeException::class.java) { ByteArray(21).inputStream().readBounded(20) }
    }
    
    private fun writeZip(manifest: String, additionalEntry: String? = null): File {
        val file = folder.newFile()
        ZipOutputStream(file.outputStream()).use { output ->
            output.putNextEntry(ZipEntry(SettingsBackupArchive.JSON))
            output.write(manifest.encodeToByteArray())
            output.closeEntry()
            if (additionalEntry != null) {
                output.putNextEntry(ZipEntry(additionalEntry))
                output.write(1)
                output.closeEntry()
            }
        }
        return file
    }
}
