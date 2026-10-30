plugins {
    // https://github.com/nordicsemi/Nordic-Gradle-Plugins/blob/main/plugins/src/main/kotlin/AndroidApplicationConventionPlugin.kt
    alias(libs.plugins.nordic.android.application)
    // https://github.com/nordicsemi/Nordic-Gradle-Plugins/blob/main/plugins/src/main/kotlin/ComposeConventionPlugin.kt
    alias(libs.plugins.nordic.feature.compose)
    // https://github.com/nordicsemi/Nordic-Gradle-Plugins/blob/main/plugins/src/main/kotlin/HiltConventionPlugin.kt
    alias(libs.plugins.nordic.feature.hilt)
}

android {
    namespace = "no.nordicsemi.android.blinky"
    defaultConfig {
        applicationId = "no.nordicsemi.android.nrfblinky"
    }
    @Suppress("UnstableApiUsage")
    androidResources {
        localeFilters += listOf("en")
    }
    flavorDimensions += listOf("mode")
    productFlavors {
        create("native") {
            isDefault = true
            dimension = "mode"
        }
        create("mock") {
            dimension = "mode"
        }
    }
}

dependencies {
    implementation(project(":scanner"))
    implementation(project(":blinky:spec"))
    implementation(project(":blinky:ui"))
    implementation(project(":blinky:ble"))

    // Applies the Nordic theme and the splash screen.
    implementation(nordic.theme)

    // Choose the Bluetooth LE client implementation, depending on the flavor.
    // See: https://github.com/nordicsemi/Kotlin-BLE-Library
    "nativeImplementation"(nordic.blek.client.android)
    "mockImplementation"(nordic.blek.client.android.mock)
    // Provide the LocalEnvironmentOwner for Composables.
    // This allows to mock permission requests in mock environment.
    implementation(nordic.blek.environment.android.compose)

    // Common logging facade.
    implementation(nordic.kotlin.log.timber)

    // AndroidX dependencies required by the :app module.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewModel.navigation3)
    // Logging framework.
    implementation(libs.timber)
    // Leak Canary lib allows to easily find memory leaks in the app.
    debugImplementation(libs.leakcanary)

    // Temporary fix:
    // After updating Kotlin to 2.4.0 there's no Hilt (Dagger) version yet updated.
    // Build fails with error:
    // [Hilt] Provided Metadata instance has version 2.4.0, while maximum supported version is 2.3.0.
    //        To support newer versions, update the kotlin-metadata-jvm library.
    ksp(libs.kotlin.metadata.jvm)

    // Unit tests of the mock flavor use the simulated Blinky device.
    "testMockImplementation"(libs.kotlin.junit)
    "testMockImplementation"(libs.kotlinx.coroutines.test)
}
