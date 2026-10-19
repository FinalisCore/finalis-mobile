plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val useMockLightserver =
    providers.gradleProperty("finalis.useMockLightserver").orElse("false").map(String::toBoolean)
val explorerBaseUrl =
    providers.gradleProperty("finalis.explorerBaseUrl").orElse("http://85.217.171.168:18080")

android {
    buildToolsVersion = "36.0.0"
    namespace = "com.finalis.mobile.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.finalis.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("boolean", "USE_MOCK_LIGHTSERVER", useMockLightserver.get().toString())
        buildConfigField("String", "EXPLORER_BASE_URL", "\"${explorerBaseUrl.get()}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
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
