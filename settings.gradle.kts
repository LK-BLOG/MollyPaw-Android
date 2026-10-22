pluginManagement {
    repositories {
        // 阿里云镜像放前面 —— 国内直连 Google/Maven 基本拉不动
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 本地仓库优先：bouncycastle 的 jar 是从 Gradle 自带的库里拷过来的
        // （见 tools/setup_local_deps.py），避免为了一个 8MB 的包走网络。
        mavenLocal()
        // 阿里云镜像放前面 —— 国内直连 Google/Maven 基本拉不动
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "MollyPawRemote"
include(":app")
