plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gv.scpreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gv.scpreader"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        val showDonate = providers.gradleProperty("showDonate").getOrElse("true").toBoolean()
        buildConfigField("boolean", "SHOW_DONATE", showDonate.toString())
        buildConfigField("String", "DONATE_URL", "\"https://ko-fi.com/guillermovidal\"")
    }

    // Release key settings live outside the repo, in ~/.gradle/gradle.properties:
    // GV_RELEASE_STORE_FILE, GV_RELEASE_STORE_PASSWORD, GV_RELEASE_KEY_ALIAS, GV_RELEASE_KEY_PASSWORD.
    val releaseStoreFile = providers.gradleProperty("GV_RELEASE_STORE_FILE").orNull
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("GV_RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("GV_RELEASE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("GV_RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseStoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                // No release key on this machine (e.g. a fresh clone): still produce an installable APK.
                logger.warn("GV_RELEASE_STORE_FILE not set: signing the release build with the debug key.")
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

tasks.withType<Test>().configureEach {
    providers.gradleProperty("gvDumpDir").orNull?.let { systemProperty("gv.dumpDir", it) }
}
