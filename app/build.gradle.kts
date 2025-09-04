plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}


android {
    namespace = "com.taskfree.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.taskfree.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "1.0.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(findProperty("RELEASE_STORE_FILE") as String)
            storePassword = findProperty("RELEASE_STORE_PASSWORD") as String
            keyAlias = findProperty("RELEASE_KEY_ALIAS") as String
            keyPassword = findProperty("RELEASE_KEY_PASSWORD") as String

        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        // This is a known lint bug with Kotlin 2.1.x + AndroidX lifecycle lint. Work around it until it’s fixed upstream:
        abortOnError = false
        disable += setOf("NullSafeMutableLiveData")
        // or: checkReleaseBuilds = false
    }

}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.material)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.reorderable)
    implementation(libs.datastore.preferences)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)
    ksp(libs.room.compiler)
}

composeCompiler {
    reportsDestination.set(layout.buildDirectory.dir("compose_reports"))
    metricsDestination.set(layout.buildDirectory.dir("compose_metrics"))
}