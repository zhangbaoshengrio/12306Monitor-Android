plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.wizpizz.ticket12306"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wizpizz.ticket12306"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "1.0.9"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    testOptions {
        unitTests.all { test ->
            // 联网的真实查询测试：12306 的证书由 CFCA 签发，JDK 自带的信任库里没有。
            // 跑的时候用 LIVE_TRUSTSTORE 指定一个只含 CFCA 根证书的信任库（密码 changeit）。
            val trust = System.getenv("LIVE_TRUSTSTORE")
            if (System.getenv("LIVE_12306") == "1" && trust != null) {
                test.jvmArgs("-Djavax.net.ssl.trustStore=$trust", "-Djavax.net.ssl.trustStorePassword=changeit")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
