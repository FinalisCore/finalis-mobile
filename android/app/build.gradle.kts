plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val useMockLightserver =
    providers.gradleProperty("finalis.useMockLightserver").orElse("false").map(String::toBoolean)
val explorerBaseUrl =
    providers.gradleProperty("finalis.explorerBaseUrl").orElse("http://85.217.171.168:18080")
// Set by the release workflow from the tag (v0.0.18 -> code 18, name 0.0.18).
val appVersionCode = providers.gradleProperty("finalis.versionCode").orElse("1").map(String::toInt)
val appVersionName = providers.gradleProperty("finalis.versionName").orElse("0.0.0-dev")
// The fixed key every published APK is signed with, so each release installs over the last one.
// Without it (local builds) Android's per-machine debug key is used.
val signingKeystore = providers.environmentVariable("FINALIS_KEYSTORE_FILE")

android {
    buildToolsVersion = "36.0.0"
    namespace = "com.finalis.mobile.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.finalis.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode.get()
        versionName = appVersionName.get()
        buildConfigField("boolean", "USE_MOCK_LIGHTSERVER", useMockLightserver.get().toString())
        buildConfigField("String", "EXPLORER_BASE_URL", "\"${explorerBaseUrl.get()}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (signingKeystore.isPresent) {
            create("finalis") {
                storeFile = file(signingKeystore.get())
                storePassword = providers.environmentVariable("FINALIS_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("FINALIS_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("FINALIS_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            if (signingKeystore.isPresent) signingConfig = signingConfigs.getByName("finalis")
        }
        release {
            // Cleartext HTTP is limited to known endpoint hosts by res/xml/network_security_config.xml.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

gradle.taskGraph.whenReady {
    val isReleaseBuild =
        allTasks.any { task ->
            task.path == ":app:assembleRelease" ||
                task.path == ":app:bundleRelease" ||
                task.path == ":app:packageRelease"
        }
    if (isReleaseBuild) {
        check(!useMockLightserver.get()) {
            "Release builds must disable mock lightserver mode. Pass -Pfinalis.useMockLightserver=false."
        }
        check(explorerBaseUrl.get().startsWith("https://")) {
            "Release builds must use an HTTPS explorer URL. Pass -Pfinalis.explorerBaseUrl=https://host."
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:crypto"))
    implementation(project(":core:storage"))
    implementation(project(":core:wallet"))
    implementation(project(":data:lightserver"))
    implementation(project(":data:wallet"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:home"))
    implementation(project(":feature:receive"))
    implementation(project(":feature:send"))
    implementation(project(":feature:history"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.zxing.core)
    testImplementation(kotlin("test"))
    debugImplementation(libs.androidx.compose.ui.tooling)
}
