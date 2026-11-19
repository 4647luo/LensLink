# Third-party notices

## Runtime dependencies

The application declares these direct libraries. AndroidX, Jetpack Compose, Navigation, and Gson are distributed under Apache-2.0; retain their upstream notices when distributing binaries.

| Component | Declared version | License / upstream |
| --- | --- | --- |
| Gson | 2.10.1 | [Apache-2.0](https://github.com/google/gson/blob/main/LICENSE) |
| AndroidX ExifInterface | 1.3.7 | [Apache-2.0](https://developer.android.com/jetpack/androidx) |
| AndroidX DocumentFile | 1.0.1 | [Apache-2.0](https://developer.android.com/jetpack/androidx) |
| AndroidX Core KTX | 1.13.1 | [Apache-2.0](https://developer.android.com/jetpack/androidx) |
| AndroidX Lifecycle Runtime KTX | 2.8.2 | [Apache-2.0](https://developer.android.com/jetpack/androidx) |
| AndroidX Activity Compose | 1.9.0 | [Apache-2.0](https://developer.android.com/jetpack/androidx) |
| Jetpack Compose libraries | BOM 2024.04.01 | [Apache-2.0](https://developer.android.com/jetpack/androidx) |
| AndroidX Navigation Compose | 2.7.7 | [Apache-2.0](https://developer.android.com/jetpack/androidx) |

Test-only dependencies are JUnit 4.13.2 ([EPL-1.0](https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt)), AndroidX Test JUnit 1.3.0, and Espresso 3.7.0 (AndroidX libraries use Apache-2.0). They are not application runtime features.

Gradle resolves transitive AndroidX and test dependencies from the repositories configured in `settings.gradle.kts`. This notice lists declared direct dependencies, not a generated Software Bill of Materials. Recheck the resolved dependency graph and each artifact's license before releasing a binary or adding dependencies.

## Protocol research references

- `open-nikonz/zf-capture-one-helper` — [source repository](https://github.com/open-nikonz/zf-capture-one-helper), [Apache-2.0 license](https://github.com/open-nikonz/zf-capture-one-helper/blob/main/LICENSE). Its public pairing fixtures/research were consulted to corroborate the Nikon pairing operation sequence. This application does not bundle that repository's source or fixture files.
- `libgphoto2` — [source repository](https://github.com/gphoto/libgphoto2), [license and notices](https://github.com/gphoto/libgphoto2/blob/master/COPYING). It is not linked, packaged, or declared as a dependency in this application. The use of protocol facts or operation codes does not mean libgphoto2 code is included.

## Trademarks

Nikon, Nikon Z, Z 30, and other product names belong to their respective owners and are used only to describe compatibility. This project is independent and is not endorsed by Nikon.
