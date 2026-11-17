plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// O chat de suporte (Firebase) só é ligado quando o google-services.json existe. Assim o projeto
// compila e roda sem ele (o suporte aparece desligado, com o e-mail de contato) e o arquivo não
// precisa ir para o repositório, que é público. Ver README → Suporte.
val firebaseConfig = file("google-services.json")
if (firebaseConfig.exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Cada APK/AAB gerado pelo CI ganha um número novo (o número da execução do GitHub Actions). Ele
// aparece na tela inicial do app e no título da release: é assim que se confere se a atualização
// entrou. O Play exige versionCode sempre crescente — o número da execução garante isso.
val ciRun: Int? = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

// Chave de publicação (upload key) vinda de variáveis de ambiente/secrets do CI. Fora do CI, ou sem
// os secrets, o release sai assinado com a chave de teste — serve para instalar no celular, mas o
// Play recusa (ele exige build assinado com a chave de upload). A chave nunca entra no repositório.
val uploadKeystore = System.getenv("MAPP_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "br.com.mapp"
    // Android 16: obrigatório para publicar ou atualizar no Google Play desde 31/08/2026.
    compileSdk = 36

    defaultConfig {
        applicationId = "br.com.mapp"
        minSdk = 26
        targetSdk = 36
        versionCode = ciRun ?: 1
        versionName = if (ciRun != null) "0.3.$ciRun" else "0.3-dev"
        // Só celulares ARM: o OCR traz bibliotecas nativas por arquitetura e o APK ficaria o dobro.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        // Chave fixa de teste: todo APK do CI sai com a mesma assinatura e instala por cima da
        // versão anterior. Não serve para o Play.
        getByName("debug") {
            storeFile = file("mapp-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = uploadKeystore
                storePassword = System.getenv("MAPP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MAPP_KEY_ALIAS")
                keyPassword = System.getenv("MAPP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 desligado até ser testado no celular: encolher o app sem testar arrisca quebrar
            // justamente a versão que vai para a loja. Para ligar depois: isMinifyEnabled = true e
            // conferir leitor, Card e chat num APK release instalado no aparelho.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
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
    // ML Kit: se o Play Console avisar "Tamanho da página de memória: não compatível com 16 KB",
    // a correção é subir esta versão (as bibliotecas nativas do ML Kit é que precisam do alinhamento).
    implementation(libs.mlkit.text)

    // Assinatura mensal pela Google Play.
    implementation(libs.billing)

    // Chat de suporte.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
