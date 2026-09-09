plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "ru.evrasia.research"
    compileSdk = 35
    defaultConfig {
        applicationId = "web.research"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("WEB_RESEARCH_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("WEB_RESEARCH_VERSION") ?: "dev"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
