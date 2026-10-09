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

    lint {
        // i18n 批 2 起：翻译完整性门禁（CI 的 lintDebug 步骤执行），双向——
        // MissingTranslation：默认侧有键而 zh 缺；ExtraTranslation：zh 有键而默认
        // 缺（AAPT2 还会剥掉无默认值的资源，getString 运行时崩）。两方向均已
        // 阴性对照实证拦截（见批 2 提交信息）。
        fatal += listOf("MissingTranslation", "ExtraTranslation")
        // 既有问题固定进基线（4 处 Error 级 NewApi：isExternalStorageManager /
        // WindowInsets.CONSUMED 需 API 30 而 minSdk 29——批 2 前就存在，是否修
        // 另行决定）；基线外的增量问题照常红。
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}
