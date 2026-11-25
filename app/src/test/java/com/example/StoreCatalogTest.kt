package com.example

import com.example.data.ImageKind
import com.example.data.StoreCatalog
import com.example.data.StoreCategory
import com.example.data.VmProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreCatalogTest {

    @Test
    fun storeCatalogContainsExpectedCategoriesAndPermissiveRiscvItems() {
        val categories = StoreCategory.values().toList()
        assertEquals(2, categories.size)
        assertTrue(categories.contains(StoreCategory.ISO))
        assertTrue(categories.contains(StoreCategory.KERNEL))

        val allItems = StoreCatalog.items
        assertTrue("Store catalog must contain built-in diagnostic ROM", allItems.isNotEmpty())
        assertNotNull(StoreCatalog.findById("kernel_diag_rom"))
        assertNotNull(StoreCatalog.findById("alpine_rv64"))
        assertNotNull(StoreCatalog.findById("iso_alpine_rv64"))

        val alpineRootfs = StoreCatalog.findById("alpine_rv64")!!
        assertEquals("Alpine RV64", alpineRootfs.name)
        assertEquals("riscv64", alpineRootfs.architecture)
        assertEquals(ImageKind.DISK_IMAGE, alpineRootfs.targetKind)
        assertTrue(alpineRootfs.downloadUrl.contains("dl-cdn.alpinelinux.org"))
        assertTrue(alpineRootfs.downloadUrl.contains("riscv64"))

        val allowedLicenses = setOf("Apache-2.0 / MIT", "Apache-2.0", "MIT")
        for (item in allItems) {
            assertTrue("Catalog item ${item.id} must have permissive license: ${item.license}", item.license in allowedLicenses)
            assertTrue("Catalog item ${item.id} must target RISC-V: ${item.architecture}", item.architecture.startsWith("riscv"))
        }
    }

    @Test
    fun storeCatalogItemPropertiesAndMirrorsAreWellFormed() {
        for (item in StoreCatalog.items) {
            assertTrue("Item ID must be non-empty", item.id.isNotBlank())
            assertTrue("Item name must be non-empty", item.name.isNotBlank())
            assertTrue("Description must be non-empty", item.description.isNotBlank())
            assertTrue("Formatted size must be non-empty", item.formattedSize.isNotBlank())
            assertTrue("Filename must be non-empty", item.filename.isNotBlank())

            if (item.id == "kernel_diag_rom") {
                assertTrue("Diagnostic ROM has empty download URL", item.downloadUrl.isEmpty())
            } else {
                assertTrue(
                    "Download URL for '${item.name}' must be http(s)",
                    item.downloadUrl.startsWith("http://") || item.downloadUrl.startsWith("https://")
                )
                assertTrue("Size bytes must be positive for '${item.name}'", item.sizeBytes > 0)
                for (mirror in item.mirrorUrls) {
                    assertTrue(
                        "Mirror URL for '${item.name}' must be http(s)",
                        mirror.startsWith("http://") || mirror.startsWith("https://")
                    )
                }
            }
        }

        val diagRom = StoreCatalog.findById("kernel_diag_rom")
        assertNotNull(diagRom)
        requireNotNull(diagRom)
        assertEquals(ImageKind.BUILTIN_ROM, diagRom.targetKind)
        assertEquals("riscv32", diagRom.architecture)
        assertEquals("Apache-2.0 / MIT", diagRom.license)
    }

    @Test
    fun vmProfileArchitectureAndEngineFieldsDefaults() {
        val profile = VmProfile(
            name = "Test Profile",
            kernelImageId = 1L
        )

        assertEquals(VmProfile.ARCH_RISCV64, profile.architecture)
        assertEquals(VmProfile.ENGINE_KERNO_KOTLIN, profile.engineType)
        assertEquals("riscv64", VmProfile.ARCH_RISCV64)
        assertEquals("kerno_kotlin", VmProfile.ENGINE_KERNO_KOTLIN)
        assertEquals(VmProfile.BOOT_SLOT_ISO, profile.primaryBootSlot)
    }

    @Test
    fun storeCatalogCategoryLabelsAndFiltering() {
        assertEquals("ISO / Disk OS", StoreCategory.ISO.label)
        assertEquals("Kernel & Bare-Metal", StoreCategory.KERNEL.label)

        // Verify that StoreItem target kinds correspond to category
        for (iso in StoreCatalog.isoItems) {
            assertTrue(iso.targetKind == ImageKind.ISO_IMAGE || iso.targetKind == ImageKind.DISK_IMAGE)
        }
        for (kernel in StoreCatalog.kernelItems) {
            assertTrue(
                kernel.targetKind == ImageKind.KERNEL_IMAGE ||
                    kernel.targetKind == ImageKind.BUILTIN_ROM
            )
        }
    }
}
