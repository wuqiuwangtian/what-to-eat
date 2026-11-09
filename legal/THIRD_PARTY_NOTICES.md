# Third-party notices

Project-owned code, documentation and the hand-curated catalog are MIT licensed (see PROJECT-LICENSE.txt). The food names and typical ingredient/category labels were compiled for this project; no recipe texts or images were copied from the surveyed projects. Research links are references, not bundled implementations.

The application/build uses these upstream components under their own licenses:

| Component | Upstream | License | Distribution |
| --- | --- | --- | --- |
| Kotlin standard library and Kotlin tooling | https://github.com/JetBrains/kotlin | Apache-2.0 | Runtime/tooling |
| AndroidX Compose, Activity, Lifecycle, Room and annotations | https://android.googlesource.com/platform/frameworks/support/ | Apache-2.0 | Runtime |
| kotlinx.coroutines | https://github.com/Kotlin/kotlinx.coroutines | Apache-2.0 | Runtime |
| OkHttp / Okio | https://github.com/square/okhttp / https://github.com/square/okio | Apache-2.0 | Runtime |
| Guava ListenableFuture | https://github.com/google/guava | Apache-2.0 (inherited upstream; artifact POM omits declaration) | Runtime |
| JSpecify annotations | https://github.com/jspecify/jspecify | Apache-2.0 | Runtime |
| Gradle Wrapper | https://github.com/gradle/gradle | Apache-2.0 | Build scripts and wrapper |
| Android Gradle Plugin and KSP | https://android.googlesource.com/platform/tools/base/ / https://github.com/google/ksp | Apache-2.0 | Build only |
| JUnit 4 | https://github.com/junit-team/junit4 | EPL-1.0 | Test only |
| Hamcrest | https://github.com/hamcrest/JavaHamcrest | BSD-3-Clause | Test only |
| JSON-java | https://github.com/stleary/JSON-java | Public domain | JVM test only; Android uses platform org.json |
| AndroidX Benchmark, ProfileInstaller, UI Automator and Perfetto tooling | https://android.googlesource.com/platform/frameworks/support/ | Apache-2.0; retain bundled upstream notices | Isolated benchmark/test only |
| AndroidX Test | https://android.googlesource.com/platform/frameworks/testing/ | Apache-2.0 | Test APK only |

All upstream trademarks and copyrights remain with their respective owners. Apache-2.0 text is included in Apache-2.0.txt. Retain upstream source headers, license and NOTICE resources when redistributing. This inventory separates runtime and test/build components; it is not a claim that all transitive artifacts have received a final release audit.

Before a formal release, generate and review the resolved runtime inventory, retain any additional notices, and update this file. Authoritative versions are pinned in the Gradle build files. No code from the six surveyed peer projects or model weights is currently bundled.

Resolved development runtime: 71 artifacts, recorded in runtime-dependencies.tsv with artifact hashes. Resolved release runtime: 67 artifacts, recorded separately in release-runtime-dependencies.tsv; debug-only tooling is excluded. The Guava artifact omits the license in its own POM; upstream Guava licensing must be retained. Final notice review remains a release gate.
