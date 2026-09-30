import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 从 keystore.properties 读取签名信息（没有这个文件也能编译，只是出的是未签名包）
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    // namespace：代码里 R 类所属的包名
    namespace = "com.yxz.timetable"

    // compileSdk = 「编译时用哪一版的系统 API」。装的是 android-35 平台，所以填 35。
    // 它只影响编译期能看到哪些 API，不会改变运行时的最低要求（那是 minSdk 管的）。
    compileSdk = 35

    // 显式钉死 build-tools 版本。不写的话 AGP 会用它认为最合适的版本，
    // 如果那个版本本机没有，它就会去 dl.google.com 下载 —— 而这里正好连不上。
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.yxz.timetable"
        minSdk = 26          // Android 8.0，覆盖 2017 年后的所有手机
        targetSdk = 34       // Android 14
        // versionCode 和 versionName 是两回事，别只改一个：
        //   versionCode  整数，给系统判断「新不新」用。**每次发版都必须 +1**，
        //                否则系统不认为这是新版本，可能不让覆盖安装。
        //   versionName  字符串，给人看的，随便怎么起都行（1.8.2 / 1.9-beta…）。
        //
        // 只改 versionName 不改 versionCode 是个很常见的坑：
        // 装上去看着是「新版本」，但系统层面新旧关系没变。
        versionCode = 17
        versionName = "1.10.2"
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 开启 R8 代码压缩 + 资源收缩。
            //
            // 以前关着（怕 R8 误删反射用到的类），现在敢开了，判据很明确：
            //   · 全项目没有一处反射（没有 Class.forName / newInstance）
            //   · 没有序列化框架，JSON 走 Android 系统自带的 org.json（平台提供，不受影响）
            //   · ViewBinding 是编译期生成的直接引用，不需要 keep 规则
            //   · 四大组件由 AAPT/AGP 自动生成 keep 规则
            // 收益：APK 明显变小、无需校验的代码变少（启动略微变快）。
            //
            // 每次改完发版前 assembleRelease 一次，装机冒烟（导入导出 / 通知 / 闹钟）
            // 仍然不可省 —— R8 的风险从来不是「编译不过」，而是「运行时才崩」。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
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
        // viewBinding：自动为每个 layout XML 生成一个类型安全的绑定类，
        // 以后写 binding.tvTitle 就行，不用 findViewById 也不用强转类型
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // 单元测试。跑在电脑的 JVM 上，不需要手机 —— 所以验证核心算法只要几秒。
    testImplementation("junit:junit:4.13.2")

    // Android 自带的 org.json 在单元测试里是个「空壳」，一调用就抛
    // "not mocked"。加一份真正的实现到测试classpath 上覆盖它，
    // 这样解析 JSON 的代码才能被真正测到。
    testImplementation("org.json:json:20240303")
}
