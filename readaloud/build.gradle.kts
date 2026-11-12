// Versions match the Chaquopy 17.2 demo app, which Chaquopy tests in CI.
// AGP 9 compiles Kotlin itself, so no separate Kotlin plugin is needed.
plugins {
    id("com.android.application") version "9.5.0" apply false
    id("com.chaquo.python") version "17.2.0" apply false
}
