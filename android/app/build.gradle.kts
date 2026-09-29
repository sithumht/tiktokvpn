import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

val releaseKeystore = providers.environmentVariable("ANDROID_KEYSTORE_PATH")
    .map(String::trim)
    .filter { it.isNotEmpty() }
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS")
    .map(String::trim)
    .filter { it.isNotEmpty() }
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD")
val debugKeystore = providers.environmentVariable("TIKTOKVPN_DEBUG_KEYSTORE")
    .map(String::trim)
    .filter { it.isNotEmpty() }
val releaseSigning = releaseKeystore.isPresent && releaseKeyAlias.isPresent
val versionNameValue = providers.environmentVariable("TIKTOKVPN_VERSION_NAME").orElse("1.0.0")
val versionCodeValue = providers.environmentVariable("TIKTOKVPN_VERSION_CODE").map(String::toInt).orElse(1)
val coreVersionValue = providers.environmentVariable("TIKTOKVPN_CORE_VERSION").orElse("local")

android {
    namespace = "com.tiktokvpn.app"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.tiktokvpn.app"
        minSdk = 26
        targetSdk = 37
        versionCode = versionCodeValue.get()
        versionName = versionNameValue.get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "CORE_VERSION", "\"${coreVersionValue.get()}\"")
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (debugKeystore.isPresent) {
            getByName("debug") {
                storeFile = file(debugKeystore.get())
            }
        }
        if (releaseSigning) {
            create("release") {
                storeFile = file(releaseKeystore.get())
                keyAlias = releaseKeyAlias.get()
                storePassword = releaseStorePassword.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            if (releaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }

    androidResources {
        localeFilters += listOf("en")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-compiler:2.60.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.4.0")

    implementation("androidx.datastore:datastore-preferences:1.2.1")

    val mobileAar = file("libs/tiktokvpn.aar")
    if (mobileAar.isFile) {
        implementation(files(mobileAar))
    }

    testImplementation("junit:junit:4.13.2")
    // The platform ships org.json as stubs; unit tests need the real thing.
    testImplementation("org.json:json:20260814")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
