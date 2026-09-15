// ============================================================
// settings.gradle.kts —— 整个工程的「目录索引」
// 作用：告诉 Gradle 去哪里下载依赖、这个工程包含哪些模块
// 类比：相当于前端项目里 package.json 的 "workspaces" + .npmrc 的 registry
// ============================================================

pluginManagement {
    repositories {
        // 优先走阿里云镜像（国内快），拿不到再回落到官方源
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // FAIL_ON_PROJECT_REPOS：禁止在子模块里单独写仓库，统一在这里管，避免版本混乱
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "Timetable"
include(":app")
