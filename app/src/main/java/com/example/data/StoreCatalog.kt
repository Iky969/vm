package com.example.data

/**
 * Categories available in the KernoVM Store.
 */
enum class StoreCategory(val label: String) {
    ISO("ISO / Disk OS"),
    KERNEL("Kernel & Bare-Metal")
}

/**
 * Catalog item for direct on-demand downloading in KernoVM Store.
 */
data class StoreItem(
    val id: String,
    val name: String,
    val description: String,
    val category: StoreCategory,
    val downloadUrl: String,
    val mirrorUrls: List<String> = emptyList(),
    val sizeBytes: Long,
    val license: String,
    val architecture: String,
    val targetKind: ImageKind,
    val expectedSha256: String? = null,
    val filename: String
) {
    /**
     * Human-readable formatted size string (e.g. "17 MB", "1.4 MB").
     */
    val formattedSize: String
        get() = ImageRepository.formatMb(sizeBytes)
}

/**
 * Curated repository of verified direct downloads for KernoVM.
 */
object StoreCatalog {

    val items: List<StoreItem> = listOf(
        // ==========================================
        // Category: Pre-installed Rootfs & Installer ISO (RISC-V 64)
        // ==========================================
        StoreItem(
            id = "alpine_rv64",
            name = "Alpine RV64",
            description = "Pre-installed rootfs resmi Alpine Linux 3.24.4 (riscv64 minirootfs) untuk Virtual Disk Target (/dev/vdb, read-write). Siap unduh on-demand via CDN resmi Alpine Linux.",
            category = StoreCategory.ISO,
            downloadUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/riscv64/alpine-minirootfs-3.24.4-riscv64.tar.gz",
            mirrorUrls = listOf(
                "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/riscv64/alpine-minirootfs-3.24.4-riscv64.tar.gz"
            ),
            sizeBytes = 3451625L,
            license = "MIT",
            architecture = "riscv64",
            targetKind = ImageKind.DISK_IMAGE,
            expectedSha256 = "010683db6c73b3ad1c334fc8f93f64ae748ef67ae6d69e964929aeaf8517bff3",
            filename = "alpine-minirootfs-3.24.4-riscv64.tar.gz"
        ),
        StoreItem(
            id = "iso_alpine_rv64",
            name = "Alpine Linux Standard RV64 (ISO)",
            description = "Citra ISO installer resmi Alpine Linux 3.24.4 arsitektur RISC-V 64-bit (RV64) untuk slot media installer VirtIO-blk (/dev/vda, read-only).",
            category = StoreCategory.ISO,
            downloadUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/riscv64/alpine-standard-3.24.4-riscv64.iso",
            mirrorUrls = listOf(
                "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/riscv64/alpine-standard-3.24.4-riscv64.iso"
            ),
            sizeBytes = 58312704L,
            license = "MIT",
            architecture = "riscv64",
            targetKind = ImageKind.ISO_IMAGE,
            expectedSha256 = "0975261d6bbedd200e256b66edb9eeb9e2db5547ff5b51087e3e00aebbb802d5",
            filename = "alpine-standard-3.24.4-riscv64.iso"
        ),

        // ==========================================
        // Category: Kernels & Bare-Metal
        // ==========================================
        StoreItem(
            id = "kernel_diag_rom",
            name = "Diagnostic ROM (Bawaan)",
            description = "ROM monitor diagnostik kode mesin RV32IMA bawaan offline langsung aktif tanpa unduhan (< 4 KB).",
            category = StoreCategory.KERNEL,
            downloadUrl = "",
            mirrorUrls = emptyList(),
            sizeBytes = 4096L,
            license = "Apache-2.0 / MIT",
            architecture = "riscv32",
            targetKind = ImageKind.BUILTIN_ROM,
            expectedSha256 = null,
            filename = "builtin_rom.bin"
        )
    )

    val isoItems: List<StoreItem> get() = items.filter { it.category == StoreCategory.ISO }
    val kernelItems: List<StoreItem> get() = items.filter { it.category == StoreCategory.KERNEL }

    fun findById(id: String): StoreItem? = items.firstOrNull { it.id == id }
}
