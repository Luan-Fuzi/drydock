plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.drydock.prototype"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.drydock.prototype"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // W^X（targetSdk 29+）下应用数据目录不可执行，唯一可 exec 的位置是
    // nativeLibraryDir；useLegacyPackaging 保证 lib*.so 以真实文件落地该目录。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}
