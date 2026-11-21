package com.niki914.zafiro.app.ui.devtools

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

internal const val ARTIFACT_MAX_BYTES = 256L * 1024 * 1024
private const val MAX_ARCHIVE_ENTRIES = 24_000
private const val MAX_DIRECTORY_BYTES = 8 * 1024 * 1024
private const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
private const val MAX_RESOURCES_BYTES = 32 * 1024 * 1024
internal const val ARTIFACT_MAX_SO_BYTES = 32 * 1024 * 1024
private const val MAX_TOTAL_LIBRARY_BYTES = 128L * 1024 * 1024
private const val MAX_LIBRARIES = 256
private const val MAX_ITEMS = 12_000
private const val MAX_NAME = 512

internal enum class ArtifactIssue { FORMAT, LIMIT }
internal class ArtifactReadException(val issue: ArtifactIssue) : IOException()

internal enum class ArtifactKind { APK, SO }

internal data class ArtifactComponent(val kind: ArtifactComponentKind, val name: String)
internal enum class ArtifactComponentKind { ACTIVITY, SERVICE, RECEIVER, PROVIDER }
internal data class ArtifactDependency(val library: String?, val name: String)

internal data class ArtifactManifest(
    val packageName: String,
    val permissions: Set<String>,
    val components: Set<ArtifactComponent>,
)

internal data class ArtifactMetadata(
    val kind: ArtifactKind,
    val bytes: Long,
    val manifest: ArtifactManifest? = null,
    val abis: Set<String>,
    val dependencies: Set<ArtifactDependency>,
)

internal data class ArtifactChanges<T>(val added: List<T>, val removed: List<T>)
internal data class ArtifactDiff(
    val permissions: ArtifactChanges<String>,
    val abis: ArtifactChanges<String>,
    val components: ArtifactChanges<ArtifactComponent>,
    val dependencies: ArtifactChanges<ArtifactDependency>,
    val sizeDelta: Long,
)

internal fun artifactCompare(old: ArtifactMetadata, new: ArtifactMetadata): ArtifactDiff? {
    if (old.kind != new.kind) return null
    fun <T> changes(before: Set<T>, after: Set<T>, order: Comparator<T>) =
        ArtifactChanges((after - before).sortedWith(order), (before - after).sortedWith(order))
    return ArtifactDiff(
        permissions = changes(old.manifest?.permissions.orEmpty(), new.manifest?.permissions.orEmpty(), naturalOrder()),
        abis = changes(old.abis, new.abis, naturalOrder()),
        components = changes(old.manifest?.components.orEmpty(), new.manifest?.components.orEmpty(),
            compareBy(ArtifactComponent::kind, ArtifactComponent::name)),
        dependencies = changes(old.dependencies, new.dependencies,
            compareBy(ArtifactDependency::library, ArtifactDependency::name)),
        sizeDelta = new.bytes - old.bytes,
    )
}

internal fun artifactCopyBounded(
    input: InputStream,
    output: OutputStream,
    limit: Long,
    checkpoint: () -> Unit = {},
): Long {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        checkpoint()
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - total + 1).toInt())
        if (count < 0) return total
        if (count == 0) throw IOException()
        total += count
        artifactLimit(total <= limit)
        output.write(buffer, 0, count)
    }
}

internal fun artifactRead(
    file: File,
    checkpoint: () -> Unit = {},
    manifestReader: (File) -> ArtifactManifest,
): ArtifactMetadata = RandomAccessFile(file, "r").use { input ->
    val size = input.length()
    artifactLimit(size <= ARTIFACT_MAX_BYTES)
    val source = ArtifactBytes(size) { offset, bytes ->
        input.seek(offset)
        input.readFully(bytes)
    }
    val magic = source.read(0, 4).getInt(0)
    when (magic) {
        0x464c457f -> {
            artifactLimit(size <= ARTIFACT_MAX_SO_BYTES)
            val elf = artifactElf(source)
            ArtifactMetadata(ArtifactKind.SO, size, abis = setOf(elf.abi),
                dependencies = elf.needed.mapTo(mutableSetOf()) { ArtifactDependency(null, it) })
        }
        0x04034b50 -> {
            artifactCheckZipDirectory(source)
            artifactApk(file, size, checkpoint, manifestReader)
        }
        else -> artifactInvalid()
    }
}

// Preflight the classic ZIP directory before ZipFile builds its index. ZIP64 and multi-disk ZIPs are outside scope.
private fun artifactCheckZipDirectory(source: ArtifactBytes) {
    val length = minOf(source.size, 22 + 65_535L).toInt()
    val tailOffset = source.size - length
    val tail = source.read(tailOffset, length)
    val end = (length - 22 downTo 0).firstOrNull {
        tail.getInt(it) == 0x06054b50 && tail.u16(it + 20) == length - it - 22
    } ?: artifactInvalid()
    val entries = tail.u16(end + 10)
    val bytes = tail.u32(end + 12)
    val offset = tail.u32(end + 16)
    artifactFormat(tail.u16(end + 4) == 0 && tail.u16(end + 6) == 0 && tail.u16(end + 8) == entries)
    artifactLimit(entries in 1..MAX_ARCHIVE_ENTRIES && bytes <= MAX_DIRECTORY_BYTES)
    artifactFormat(offset + bytes == tailOffset + end && bytes >= entries * 46L)
    val directory = source.read(offset, bytes.toInt())
    var record = 0
    repeat(entries) {
        artifactFormat(record <= directory.limit() - 46 && directory.getInt(record) == 0x02014b50)
        artifactFormat(directory.u16(record + 34) == 0)
        record += 46 + directory.u16(record + 28) + directory.u16(record + 30) + directory.u16(record + 32)
    }
    artifactFormat(record == directory.limit())
}

private val artifactNativeEntry = Regex("lib/([A-Za-z0-9_-]{1,32})/[^/\\\\]{1,512}\\.so")

private fun artifactApk(
    file: File,
    bytes: Long,
    checkpoint: () -> Unit,
    manifestReader: (File) -> ArtifactManifest,
): ArtifactMetadata {
    val abis = mutableSetOf<String>()
    val dependencies = mutableSetOf<ArtifactDependency>()
    ZipFile(file).use { zip ->
        artifactLimit(zip.size() <= MAX_ARCHIVE_ENTRIES)
        val seen = mutableSetOf<String>()
        var expanded = 0L
        var libraryCount = 0
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            checkpoint()
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val packageMetadataLimit = when (entry.name) {
                "AndroidManifest.xml" -> MAX_MANIFEST_BYTES.toLong()
                "resources.arsc" -> MAX_RESOURCES_BYTES.toLong()
                else -> null
            }
            if (packageMetadataLimit != null) {
                artifactFormat(seen.add(entry.name))
                artifactLimit(entry.size in 1..packageMetadataLimit)
                // AssetManager may expand both the manifest and the resource table.
                val actual = zip.getInputStream(entry).use {
                    artifactCopyBounded(it, ArtifactDiscard, packageMetadataLimit, checkpoint)
                }
                artifactFormat(actual == entry.size)
                continue
            }
            val match = artifactNativeEntry.matchEntire(entry.name) ?: continue
            artifactFormat(seen.add(entry.name))
            libraryCount++
            artifactLimit(entry.name.length <= MAX_NAME && libraryCount <= MAX_LIBRARIES)
            val remaining = minOf(ARTIFACT_MAX_SO_BYTES.toLong(), MAX_TOTAL_LIBRARY_BYTES - expanded)
            artifactLimit(entry.size in 1..remaining)
            val data = ByteArray(entry.size.toInt())
            zip.getInputStream(entry).use { stream ->
                var read = 0
                while (read < data.size) {
                    checkpoint()
                    val count = stream.read(data, read, minOf(data.size - read, 64 * 1024))
                    artifactFormat(count > 0)
                    read += count
                }
                artifactFormat(stream.read() == -1)
            }
            expanded += data.size
            val elf = artifactElf(ArtifactBytes(data.size.toLong()) { offset, target ->
                data.copyInto(target, 0, offset.toInt(), offset.toInt() + target.size)
            })
            val abi = match.groupValues[1]
            artifactFormat(abi == elf.abi)
            abis += abi
            elf.needed.forEach { dependencies += ArtifactDependency(entry.name, it) }
            artifactLimit(dependencies.size <= MAX_ITEMS)
        }
        artifactFormat("AndroidManifest.xml" in seen)
    }
    checkpoint()
    val manifest = manifestReader(file)
    artifactLimit(manifest.permissions.size + manifest.components.size <= MAX_ITEMS)
    artifactLimit(manifest.packageName.length <= MAX_NAME &&
        manifest.permissions.all { it.length <= MAX_NAME } && manifest.components.all { it.name.length <= MAX_NAME })
    return ArtifactMetadata(ArtifactKind.APK, bytes, manifest, abis, dependencies)
}

private object ArtifactDiscard : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

private class ArtifactBytes(val size: Long, private val readAt: (Long, ByteArray) -> Unit) {
    fun contains(offset: Long, length: Long): Boolean =
        offset >= 0 && offset <= size && length >= 0 && length <= size - offset

    fun read(offset: Long, length: Int): ByteBuffer {
        artifactFormat(contains(offset, length.toLong()))
        return ByteBuffer.wrap(ByteArray(length).also { readAt(offset, it) }).order(ByteOrder.LITTLE_ENDIAN)
    }
}

private data class ArtifactElfInfo(val abi: String, val needed: Set<String>)
private data class ArtifactSegment(val offset: Long, val address: Long, val bytes: Long) {
    fun mapAddress(start: Long, length: Long): Long? {
        if (start < address) return null
        val relative = start - address
        if (relative > bytes || length > bytes - relative) return null
        return offset + relative
    }
}

// Program headers remain available in stripped shared objects, unlike section tables.
private fun artifactElf(source: ArtifactBytes): ArtifactElfInfo {
    val ident = source.read(0, 16)
    artifactFormat(ident.getInt(0) == 0x464c457f && ident.get(5).toInt() == 1 && ident.get(6).toInt() == 1)
    val wide = when (ident.get(4).toInt()) {
        1 -> false
        2 -> true
        else -> artifactInvalid()
    }
    val headerBytes = if (wide) 64 else 52
    val header = source.read(0, headerBytes)
    artifactFormat(header.u16(16) == 3 && header.u32(20) == 1L)
    val abi = when (header.u16(18)) {
        3 -> if (wide) artifactInvalid() else "x86"
        40 -> if (wide) artifactInvalid() else "armeabi-v7a"
        62 -> if (wide) "x86_64" else artifactInvalid()
        183 -> if (wide) "arm64-v8a" else artifactInvalid()
        243 -> if (wide) "riscv64" else artifactInvalid()
        else -> artifactInvalid()
    }
    val fields = if (wide) 52 else 40
    val programOffset = header.word(if (wide) 32 else 28, wide)
    val entryBytes = header.u16(fields + 2)
    val entryCount = header.u16(fields + 4)
    artifactFormat(header.u16(fields) == headerBytes && entryBytes == if (wide) 56 else 32)
    artifactLimit(entryCount in 1..1024)
    artifactFormat(programOffset >= headerBytes && source.contains(programOffset, entryBytes * entryCount.toLong()))
    val loads = mutableListOf<ArtifactSegment>()
    var dynamic: ArtifactSegment? = null
    repeat(entryCount) { index ->
        val entry = source.read(programOffset + index * entryBytes, entryBytes)
        val type = entry.getInt(0)
        if (type != 1 && type != 2) return@repeat
        val segment = ArtifactSegment(
            entry.word(if (wide) 8 else 4, wide), entry.word(if (wide) 16 else 8, wide),
            entry.word(if (wide) 32 else 16, wide),
        )
        artifactFormat(source.contains(segment.offset, segment.bytes))
        if (type == 1) loads += segment else {
            artifactFormat(dynamic == null)
            dynamic = segment
        }
    }
    artifactFormat(loads.isNotEmpty())
    val table = dynamic ?: artifactInvalid()
    artifactFormat(loads.any { it.mapAddress(table.address, table.bytes) == table.offset })
    return ArtifactElfInfo(abi, artifactNeeded(source, table, loads, wide))
}

private fun artifactNeeded(
    source: ArtifactBytes,
    table: ArtifactSegment,
    loads: List<ArtifactSegment>,
    wide: Boolean,
): Set<String> {
    val word = if (wide) 8 else 4
    val entry = 2 * word
    artifactFormat(table.bytes % entry == 0L)
    artifactLimit(table.bytes in entry.toLong()..(entry * 4096L))
    val dynamic = source.read(table.offset, table.bytes.toInt())
    val needed = mutableListOf<Long>()
    var stringAddress: Long? = null
    var stringSize: Long? = null
    var terminated = false
    for (index in 0 until dynamic.capacity() step entry) {
        if (dynamic.tag(index, wide) == 0L) {
            terminated = true
            break
        }
        when (dynamic.tag(index, wide)) {
            1L -> {
                artifactLimit(needed.size < 512)
                needed += dynamic.word(index + word, wide)
            }
            5L -> {
                artifactFormat(stringAddress == null)
                stringAddress = dynamic.word(index + word, wide)
            }
            10L -> {
                artifactFormat(stringSize == null)
                stringSize = dynamic.word(index + word, wide)
            }
        }
    }
    artifactFormat(terminated)
    if (needed.isEmpty()) return emptySet()
    val address = stringAddress ?: artifactInvalid()
    val size = stringSize ?: artifactInvalid()
    artifactLimit(size in 1..(4L * 1024 * 1024))
    val offsets = loads.mapNotNull { it.mapAddress(address, size) }.distinct()
    val offset = offsets.singleOrNull() ?: artifactInvalid()
    val strings = source.read(offset, size.toInt()).array()
    return needed.mapTo(mutableSetOf()) { value ->
        artifactFormat(value < size)
        val start = value.toInt()
        var end = start
        while (end < strings.size && end - start <= MAX_NAME && strings[end].toInt() != 0) end++
        artifactFormat(end > start && end < strings.size && end - start <= MAX_NAME)
        artifactFormat((start until end).all { strings[it].toInt() in 0x20..0x7e })
        String(strings, start, end - start, Charsets.US_ASCII)
    }
}

private fun ByteBuffer.u16(at: Int) = getShort(at).toInt() and 0xffff
private fun ByteBuffer.u32(at: Int) = getInt(at).toLong() and 0xffffffffL
private fun ByteBuffer.tag(at: Int, wide: Boolean) = if (wide) getLong(at) else u32(at)
private fun ByteBuffer.word(at: Int, wide: Boolean): Long = tag(at, wide).also { artifactFormat(it >= 0) }
private fun artifactFormat(ok: Boolean) { if (!ok) artifactInvalid() }
private fun artifactLimit(ok: Boolean) { if (!ok) throw ArtifactReadException(ArtifactIssue.LIMIT) }
private fun artifactInvalid(): Nothing = throw ArtifactReadException(ArtifactIssue.FORMAT)
