plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "com.example.sound_detection"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.sound_detection"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // For Python 3.11.5, you should include:
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64","x86")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }

}

// Exclude the old Google litert artifacts from every configuration:
configurations.all {
    exclude(group = "com.google.ai.edge.litert", module = "litert-api")
    exclude(group = "com.google.ai.edge.litert", module = "litert-support-api")
}

chaquopy {
    defaultConfig {
        version = "3.8"
        buildPython("C:\\Users\\Dell\\AppData\\Local\\Programs\\Python\\Python38\\python.exe") // Updated path to Python 3.9
        pip {
            install("scikit-image == 0.18.3")
            install("numpy")
            install("scipy==1.4.1")
            install("resampy==0.3.1")
            install ("librosa==0.9.2") // A slightly newer but still relatively stable older version
        }

            //install("https://github.com/librosa/librosa/archive/0.4.2.zip")
        }
    }



dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-task-audio:0.4.4") {
        exclude(group = "com.google.ai.edge.litert", module = "litert-api")
        exclude(group = "com.google.ai.edge.litert", module = "litert-support-api")
    }
    implementation("com.squareup.okhttp3:okhttp:4.11.0") //(optional, for HTTP if needed)

    // WebSocket client for ESP32 streaming
    implementation("org.java-websocket:Java-WebSocket:1.5.4")




}