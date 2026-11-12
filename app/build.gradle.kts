plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Cada APK gerado pelo CI ganha um número novo (o número da execução do GitHub Actions). Ele aparece
// na tela inicial do app e no título da release: é assim que se confere se a atualização entrou.
val ciRun: Int? = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

android {
    namespace = "br.com.mapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.mapp"
        minSdk = 26
        targetSdk = 35
        versionCode = ciRun ?: 1
        versionName = if (ciRun != null) "0.2.$ciRun" else "0.2-dev"
        // Só celulares ARM: o OCR traz bibliotecas nativas por arquitetura e o APK ficaria o dobro.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Chave fixa: todo APK do CI sai com a mesma assinatura e instala por cima da versão anterior.
    signingConfigs {
        getByName("debug") {
            storeFile = file("mapp-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug") // uso pessoal
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.coroutines.android)
    implementation(libs.osmdroid)
    implementation(libs.play.location)
    implementation(libs.mlkit.text)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
