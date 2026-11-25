# KernoVM 🐧⚙️

Pure-Kotlin RISC-V (`RV64` profile target & `RV32IMA` execution core) Linux Virtual Machine Emulator for Android, built with Jetpack Compose, Room, and Material 3.

---

## 🌟 Overview

**KernoVM** is a lightweight, pure-Kotlin emulator designed to configure RV64 virtual machine profiles, attach dual-drive VirtIO Block devices (`/dev/vda` installer ISO RO and `/dev/vdb` writable Target Disk RW with deterministic `bootindex` FDT ordering), and run No-MMU RISC-V kernels and bare-metal binaries directly on Android devices.

* **Architecture Target & Validation**: RV64 (`riscv64`) profile schema, RV64 FDT builder (`DeviceTreeBuilder`), and strict `ArchitectureValidator` filtering (rejecting non-RISC-V binaries and ISOs).
* **Dual-Drive VirtIO-MMIO v2 Storage**:
  * Slot 0 (`/dev/vda` @ `0x10001000`, read-only installer ISO).
  * Slot 1 (`/dev/vdb` @ `0x10003000`, read-write Virtual Disk Target).
  * Native **Eject ISO / Switch Primary Disk** button cleanly detaches `/dev/vda` and promotes `/dev/vdb` (`bootindex = 0`).
* **Peripherals**:
  * UART 8250 / 16550A serial console at `0x10000000`.
  * CLINT (Core Local Interruptor) timer and software interrupt at `0x11000000`.
  * SYSCON power control (`0x5555` poweroff / `0x7777` reboot) at `0x11100000`.
  * VirtIO-Net (`0x10002000`) & Simple Framebuffer display.
* **Store & Storage**: Direct official downloads including **Alpine RV64** (`riscv64` minirootfs) and **Alpine Linux Standard RV64 (ISO)** alongside Storage Access Framework (SAF) import.

---

## 🛠️ Project Origin & Licensing

* **Origin & Scaffolding**: This application was structured and scaffolded using **Google AI Studio**.
* **License**: This project is licensed under the [Apache License, Version 2.0](LICENSE).

### Attributions & Third-Party Credits
* **mini-rv32ima**: Hardware memory layout conventions and RV32IMA architectural concepts are inspired by [mini-rv32ima](https://github.com/cnlohr/mini-rv32ima) by Charles Lohr (licensed under the MIT License).
* **Fonts**: Bundled font assets (`JetBrains Mono` and `IBM Plex Sans`) are licensed under the SIL Open Font License (OFL 1.1).
* **Full Notices**: See [NOTICE](NOTICE) and [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) for complete third-party license texts and library attributions.

---

## 🚀 Building & Testing

### Local Build (with Android SDK & JDK 21 / 17)
```bash
./gradlew assembleDebug
```

### Automated CI / CD
This repository uses semantic versioning starting from **v0.1.0**. The GitHub Actions workflow (`.github/workflows/build-release.yml`) runs unit tests and builds Debug/Release APKs on `main`, and automatically publishes versioned GitHub Releases whenever a `v*` SemVer tag (e.g. `v0.1.0`) is pushed.
