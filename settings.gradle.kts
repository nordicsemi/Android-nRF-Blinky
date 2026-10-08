pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
    versionCatalogs {
        // Link: https://github.com/nordicsemi/Nordic-Gradle-Plugins
        create("libs") {
            from("no.nordicsemi.gradle:version-catalog:3.3-2")
        }
        // Link: https://github.com/nordicsemi/Nordic-Version-Catalog
        create("nordic") {
            from("no.nordicsemi.gradle:nordic-version-catalog:2026.08.00")
        }
    }
}
rootProject.name = "nRF Blinky"

include(":app")
include(":scanner")
include(":blinky:spec")
include(":blinky:ui")
include(":blinky:ble")

// Clone https://github.com/nordicsemi/Android-Common-Libraries and
// uncomment the following lines to modify source code of the Nordic Common library:
//if (file("../Android-Common-Libraries").exists()) {
//    includeBuild("../Android-Common-Libraries")
//}

// Clone https://github.com/nordicsemi/Kotlin-BLE-Library and
// uncomment the following lines to modify source code of the BLE library:
//if (file("../Kotlin-BLE-Library").exists()) {
//    includeBuild("../Kotlin-BLE-Library")
//}
