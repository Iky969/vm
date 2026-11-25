# Third-Party Licenses

KernoVM is licensed under the **Apache License, Version 2.0** (see [LICENSE](LICENSE) and [NOTICE](NOTICE)).
Below are the license texts and attributions for third-party inspirations, bundled font assets, and open-source libraries used by KernoVM.

---

## 1. mini-rv32ima (MIT License)

Hardware memory layout conventions (`0x80000000` RAM base, `0x10000000` UART, `0x11000000` CLINT, `0x11100000` SYSCON) and RV32IMA architectural concepts in `Rv32ImaCpuCore` are inspired by [cnlohr/mini-rv32ima](https://github.com/cnlohr/mini-rv32ima).

```text
MIT License

Copyright (c) 2022 CNLohr

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 2. Bundled Fonts — SIL Open Font License, Version 1.1

KernoVM bundles the following font assets in `app/src/main/res/font/`:

1. **JetBrains Mono** (`app/src/main/res/font/jetbrains_mono.ttf`, Version 2.211)
   - Repository: https://github.com/JetBrains/JetBrainsMono
   - Copyright: `Copyright 2020 The JetBrains Mono Project Authors (https://github.com/JetBrains/JetBrainsMono)`
   - Reserved Font Name(s): None declared.

2. **IBM Plex Sans** (`app/src/main/res/font/ibm_plex_sans.ttf`, Version 3.201)
   - Repository: https://github.com/IBM/plex
   - Copyright: `Copyright © 2017 IBM Corp. with Reserved Font Name "Plex"` / `Copyright 2019 IBM Corp. All rights reserved.`
   - Reserved Font Name(s): `"Plex"`

Both bundled fonts are licensed under the SIL Open Font License, Version 1.1:

```text
-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded, 
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
```

---

## 3. Runtime Dependencies (Apache License, Version 2.0)

All runtime libraries compiled into the KernoVM application APK are licensed under the **Apache License, Version 2.0** (full text in [LICENSE](LICENSE)):

| Dependency | Version | License |
|---|---|---|
| `androidx.compose:compose-bom` (Material3, Material Icons Core & Extended, UI, UI Graphics, UI Tooling Preview) | `2024.09.00` | Apache-2.0 |
| `androidx.activity:activity-compose` | `1.10.1` | Apache-2.0 |
| `androidx.core:core-ktx` | `1.18.0` | Apache-2.0 |
| `androidx.lifecycle:lifecycle-runtime-compose`, `lifecycle-runtime-ktx`, `lifecycle-viewmodel-compose` | `2.8.7` | Apache-2.0 |
| `androidx.navigation:navigation-compose` | `2.8.9` | Apache-2.0 |
| `androidx.room:room-runtime`, `room-ktx`, `room-compiler` (KSP) | `2.7.0` | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core`, `kotlinx-coroutines-android` | `1.10.2` | Apache-2.0 |
| `com.squareup.okhttp3:okhttp` | `4.10.0` | Apache-2.0 |

---

## 4. Build & Test-Only Dependencies

The following dependencies are used exclusively during development, unit testing, and instrumentation testing and are not distributed in release builds:

| Dependency | Version | License |
|---|---|---|
| `junit:junit` | `4.13.2` | Eclipse Public License 1.0 (EPL-1.0) |
| `org.robolectric:robolectric` | `4.16.1` | MIT License |
| `androidx.compose.ui:ui-test-junit4`, `ui-test-manifest`, `ui-tooling` | BOM `2024.09.00` | Apache-2.0 |
| `androidx.test:core`, `androidx.test:runner`, `androidx.test.ext:junit`, `androidx.test.espresso:espresso-core` | `1.6.1` / `1.6.2` / `1.3.0` / `3.7.0` | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | `1.10.2` | Apache-2.0 |
