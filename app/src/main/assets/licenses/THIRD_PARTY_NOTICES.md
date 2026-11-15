# Third-party notices

Mischpult is free software under the GNU General Public License, version 3 or
(at your option) any later version; see [LICENSE](LICENSE). Its complete
source code, which is also the corresponding source for every component
below that requires it, is at https://github.com/iam-peter/mischpult.

Online sources are separate plugin apps with their own licences and notices;
none of their code is part of Mischpult.

Mischpult includes the following third-party software and fonts. The full text
of every licence named here is shipped inside the app under
`assets/licenses/` (in this repository: `app/src/main/assets/licenses/`),
together with a copy of this file.

## Native audio engine (compiled into `libmischpult_engine.so`)

### SoundTouch 2.3.3
- Licence: GNU Lesser General Public License, version 2.1 (`LGPL-2.1.txt`)
- Copyright (c) Olli Parviainen
- Source: https://www.surina.net/soundtouch/ ; the unmodified sources used
  are in `app/src/main/cpp/third_party/soundtouch/`
- Linked statically. Because the whole app is free software with its source
  published, you can rebuild it with a modified SoundTouch, as the LGPL
  requires.

### Oboe 1.11.0
- Licence: Apache License 2.0 (`Apache-2.0.txt`)
- Copyright (C) 2016 The Android Open Source Project
- Source: https://github.com/google/oboe

## App libraries (Apache License 2.0, `Apache-2.0.txt`)

| Component | Version | Copyright | Source |
|---|---|---|---|
| AndroidX (Activity, Core, Lifecycle, SavedState, Window, Emoji2, Startup, Profile Installer and others) | various | The Android Open Source Project | https://developer.android.com/jetpack/androidx |
| Jetpack Compose (runtime, UI, foundation, animation, Material 3, Material icons) | BOM 2026.09.00 | The Android Open Source Project | https://developer.android.com/jetpack/compose |
| Compose Multiplatform runtime pieces (org.jetbrains.compose, org.jetbrains.androidx lifecycle/savedstate, via Coil) | various | JetBrains s.r.o. and The Android Open Source Project | https://github.com/JetBrains/compose-multiplatform |
| Kotlin standard library | 2.4.20 | JetBrains s.r.o. and Kotlin Programming Language contributors | https://github.com/JetBrains/kotlin |
| kotlinx.coroutines | 1.11.0 | JetBrains s.r.o. and contributors | https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx.serialization | 1.12.0 | JetBrains s.r.o. and contributors | https://github.com/Kotlin/kotlinx.serialization |
| JetBrains annotations | 23.0.0 | JetBrains s.r.o. | https://github.com/JetBrains/java-annotations |
| OkHttp (via Coil) | 4.12.0 | Square, Inc. | https://github.com/square/okhttp |
| Okio | 3.18.1 | Square, Inc. | https://github.com/square/okio |
| Coil | 3.6.3 | Coil Contributors | https://github.com/coil-kt/coil |
| Accompanist (drawablepainter, via Coil) | 0.37.3 | The Android Open Source Project | https://github.com/google/accompanist |
| JSpecify annotations | 1.0.0 | The JSpecify Authors | https://jspecify.dev/ |
| Guava ListenableFuture | 1.0 | The Guava Authors | https://github.com/google/guava |

None of these ships a NOTICE file in the artifacts used.

## Fonts (SIL Open Font License 1.1)

| Font | Copyright | Licence text | Source |
|---|---|---|---|
| Barlow Condensed (Regular, Medium, SemiBold, Bold) | Copyright 2017 The Barlow Project Authors | `OFL-BarlowCondensed.txt` | https://github.com/jpt/barlow |
| Share Tech Mono | Copyright (c) 2012, Carrois Type Design, Ralph du Carrois, with Reserved Font Name "Share" | `OFL-ShareTechMono.txt` | https://fonts.google.com/specimen/Share+Tech+Mono |

Both fonts are bundled unmodified.
