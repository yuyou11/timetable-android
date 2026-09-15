// ============================================================
// 根 build.gradle.kts —— 声明「用哪些插件」，但不在这里应用
// apply false 的意思：只在根工程登记版本号，真正启用交给 app 模块
// 好处：所有模块共用同一个版本号，不会出现两个模块版本打架
// ============================================================
plugins {
    // AGP 8.6 起正式支持 compileSdk 35，且最低要求 Gradle 8.7 —— 和本机装的一致。
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
