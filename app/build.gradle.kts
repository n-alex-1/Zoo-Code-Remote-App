plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.kotlin.serialization)
}

android {
	namespace = "de.xilox.zooremote.app"
	compileSdk = 34

	defaultConfig {
		applicationId = "de.xilox.zooremote.app"
		minSdk = 26
		targetSdk = 34
		versionCode = 1
		versionName = "0.2.0-session5"
	}

	buildTypes {
		release {
			isMinifyEnabled = false
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
		compose = true
	}
}

dependencies {
	val composeBom = platform(libs.compose.bom)
	implementation(composeBom)
	testImplementation(composeBom)

	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.activity.compose)
	implementation(libs.compose.ui)
	implementation(libs.compose.ui.graphics)
	implementation(libs.compose.ui.tooling.preview)
	implementation(libs.compose.material3)
	implementation(libs.compose.material.icons.extended)
	implementation(libs.navigation.compose)
	implementation(libs.lifecycle.viewmodel.compose)

	implementation(libs.okhttp)
	implementation(libs.markwon.core)
	implementation(libs.kotlinx.serialization.json)
	implementation(libs.datastore.preferences)

	debugImplementation(libs.compose.ui.tooling)
}
