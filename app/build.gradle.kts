import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.yukino1111.colorosbgmanager"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "io.github.yukino1111.colorosbgmanager"
        minSdk = 33
        targetSdk = 36
        versionCode = 6
        versionName = "1.1.3"
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        localeFilters.addAll(listOf("en", "zh"))
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties().apply {
        if (keystorePropertiesFile.exists()) {
            keystorePropertiesFile.inputStream().use { load(it) }
        }
    }

    signingConfigs {
        create("release") {
            fun secret(name: String): String? =
                keystoreProperties.getProperty(name)
                    ?: providers
                        .gradleProperty(name)
                        .orElse(providers.environmentVariable(name))
                        .orNull

            val storeFilePath = secret("storeFile") ?: secret("RELEASE_STORE_FILE")
            val storePassword = secret("storePassword") ?: secret("RELEASE_STORE_PASSWORD")
            val keyAlias = secret("keyAlias") ?: secret("RELEASE_KEY_ALIAS")
            val keyPassword = secret("keyPassword") ?: secret("RELEASE_KEY_PASSWORD") ?: storePassword
            val storeType = secret("storeType") ?: secret("RELEASE_STORE_TYPE")

            val releaseStoreFile = storeFilePath?.let {
                rootProject.file(it).takeIf { f -> f.exists() }
                    ?: file(it).takeIf { f -> f.exists() }
            }

            if (releaseStoreFile != null && !storePassword.isNullOrBlank() && !keyAlias.isNullOrBlank()) {
                storeFile = releaseStoreFile
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
                if (!storeType.isNullOrBlank()) {
                    this.storeType = storeType
                }
                enableV1Signing = true
                enableV2Signing = true
            } else {
                logger.warn("Release keystore not found. Release signing will be skipped.")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release").takeIf {
                it.storeFile != null && it.storeFile!!.exists()
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    compileOnly(libs.xposed.api)
    implementation(libs.xposed.service)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    testImplementation(libs.junit)
}
