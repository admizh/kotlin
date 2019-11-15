/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlinx.metadata.klib

import kotlinx.metadata.KmModuleFragment
import kotlinx.metadata.impl.WriteContext
import kotlinx.metadata.impl.accept
import kotlinx.metadata.klib.impl.*
import org.jetbrains.kotlin.backend.common.serialization.metadata.KlibMetadataStringTable
import org.jetbrains.kotlin.library.metadata.parseModuleHeader
import org.jetbrains.kotlin.library.metadata.parsePackageFragment
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.deserialization.NameResolverImpl

/**
 * Allows to modify the way fragments of the single package are read by [KlibMetadata.read].
 * For example, it may be convenient to join fragments into a single one.
 */
interface KlibModuleFragmentReadStrategy {
    fun processModuleParts(parts: List<KmModuleFragment>): List<KmModuleFragment>

    companion object {
        val DEFAULT = object : KlibModuleFragmentReadStrategy {
            override fun processModuleParts(parts: List<KmModuleFragment>) =
                parts
        }
    }
}

/**
 * Allows to modify the way module fragments are written by [KlibMetadata.write].
 * For example, splitting big fragments into several small one allows to improve IDE performance.
 */
interface KlibModuleFragmentWriteStrategy {
    fun processPackageParts(parts: List<KmModuleFragment>): List<KmModuleFragment>

    companion object {
        val DEFAULT = object : KlibModuleFragmentWriteStrategy {
            override fun processPackageParts(parts: List<KmModuleFragment>): List<KmModuleFragment> =
                parts
        }
    }
}

/**
 * Represents the parsed metadata of KLIB.
 */
class KlibMetadata(val moduleFragments: List<KmModuleFragment>) {

    /**
     * Serialized representation of module metadata.
     */
    class SerializedKlibMetadata(
        val header: ByteArray,
        val fragments: List<List<ByteArray>>,
        val fragmentNames: List<String>
    )

    /**
     * Specifies access to library's metadata.
     */
    interface MetadataLibraryProvider {
        val moduleHeaderData: ByteArray
        fun packageMetadataParts(fqName: String): Set<String>
        fun packageMetadata(fqName: String, partName: String): ByteArray
    }

    companion object {
        /**
         * Deserializes metadata from the given [library].
         * @param readStrategy specifies the way module fragments of a single package are modified (e.g. merged) after deserialization.
         */
        fun read(
            library: MetadataLibraryProvider,
            readStrategy: KlibModuleFragmentReadStrategy = KlibModuleFragmentReadStrategy.DEFAULT
        ): KlibMetadata {
            val moduleHeaderProto = parseModuleHeader(library.moduleHeaderData)
            val moduleHeader = moduleHeaderProto.readHeader()
            val nameResolver = NameResolverImpl(moduleHeaderProto.strings, moduleHeaderProto.qualifiedNames)
            val fileIndex = SourceFileIndexReadExtension(moduleHeader.file)
            val moduleFragments = moduleHeader.packageFragmentName.flatMap { packageFqName ->
                library.packageMetadataParts(packageFqName).map { part ->
                    val packageFragment = parsePackageFragment(library.packageMetadata(packageFqName, part))
                    KmModuleFragment().apply { packageFragment.accept(this, nameResolver, listOf(fileIndex)) }
                }.let(readStrategy::processModuleParts)
            }
            return KlibMetadata(moduleFragments)
        }
    }

    /**
     * Writes metadata back to serialized representation.
     * @param writeStrategy specifies the way module fragments are modified (e.g. split) before serialization.
     */
    fun write(
        writeStrategy: KlibModuleFragmentWriteStrategy = KlibModuleFragmentWriteStrategy.DEFAULT
    ): SerializedKlibMetadata {
        val reverseIndex = ReverseSourceFileIndexWriteExtension()
        val c = WriteContext(KlibMetadataStringTable(), listOf(reverseIndex))

        val groupedModuleFragmentsProtos = moduleFragments
            .groupBy({ it.fqNameOrFail() }, { it })
            .mapValues { writeStrategy.processPackageParts(it.value) }
            .mapValues { (_, fragments) ->
                fragments.map {
                    KlibModuleFragmentWriter(c.strings as KlibMetadataStringTable, c.contextExtensions).also(it::accept).write()
                }
            }
        val header = KlibHeader(reverseIndex.fileIndex, groupedModuleFragmentsProtos.map { it.key })
        return SerializedKlibMetadata(
            header.writeHeader(c).build().toByteArray(),
            groupedModuleFragmentsProtos.map { it.value.map(ProtoBuf.PackageFragment::toByteArray) },
            header.packageFragmentName
        )
    }

    private fun KmModuleFragment.fqNameOrFail(): String =
        fqName ?: error("Module fragment must have a fully-qualified name.")
}