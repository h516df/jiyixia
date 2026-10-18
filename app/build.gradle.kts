plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cn.jiaoban"
    compileSdk = 36
    defaultConfig {
        applicationId = "cn.jiaoban"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "0.12"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    // 正式版用本机调试证书签：这样正式版能直接覆盖安装调试版，已经记的东西不丢
    signingConfigs {
        create("release") {
            val keystore = rootProject.file("${System.getProperty("user.home")}/.android/debug.keystore")
            if (keystore.isFile) {
                storeFile = keystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging {
        resources.excludes.add("META-INF/{AL2.0,LGPL2.1}")
    }
}

// 让单测里的 println 直接打在控制台，方便看探针输出
tasks.withType<Test>().configureEach {
    testLogging { showStandardStreams = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
