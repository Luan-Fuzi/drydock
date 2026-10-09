plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.drydock.prototype"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.drydock"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
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

    // 正式签名：keystore 不入库，经环境变量注入（本地自设；CI 由 release workflow
    // 从 Secrets 解码）。缺变量时 release 打包直接失败，不回落 debug 签名。
    signingConfigs {
        create("release") {
            System.getenv("DRYDOCK_KEYSTORE")?.let { path ->
                storeFile = file(path)
                storePassword = System.getenv("DRYDOCK_KEYSTORE_PASSWORD")
                keyAlias = "drydock"
                // PKCS12 的 key 与 store 共用一个密码
                keyPassword = System.getenv("DRYDOCK_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        // debug 包独立包名：与用户手机上的正式包并存，真机验收不覆盖用户环境
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.getByName("release")
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
        // 既有问题固定进基线（余 2 处 Error 级 NewApi：setApplicationLocales 运行时
        // 已按 LocaleManager 非空守卫，lint 识别不了）；基线外的增量问题照常红。
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}
