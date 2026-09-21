import java.util.Properties
import com.android.build.api.variant.impl.VariantOutputImpl

plugins {
    id("com.android.application")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val signingProps = if (keystorePropertiesFile.exists()) {
    Properties().apply { load(keystorePropertiesFile.inputStream()) }
} else {
    null
}

android {
    namespace = "moe.lovefirefly.bzk.wetypeext"
    compileSdk = 37

    defaultConfig {
        applicationId = "moe.lovefirefly.bzk.wetypeext"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-probe"
    }

    signingConfigs {
        if (signingProps != null) {
            create("appSign") {
                keyAlias = signingProps["keyAlias"] as String
                keyPassword = signingProps["keyPassword"] as String
                storeFile = rootProject.file(signingProps["storeFile"] as String)
                storePassword = signingProps["storePassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            if (signingProps != null) signingConfig = signingConfigs.getByName("appSign")
        }
        release {
            if (signingProps != null) signingConfig = signingConfigs.getByName("appSign")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as VariantOutputImpl).outputFileName.set(
                "BetterZUIKey-WeTypeExt-v${output.versionName.get()}.apk"
            )
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:101.0.0")
    implementation("io.github.libxposed:service:101.0.0")
    implementation("androidx.recyclerview:recyclerview:1.1.0")
    implementation("com.google.android.material:material:1.10.0")
}
