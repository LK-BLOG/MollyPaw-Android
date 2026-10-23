// 仓库顺序按场景切。
//
// 为什么不能把阿里云写死在前面前面：镜像一旦返回 5xx（比如 502），
// Gradle 会把整个仓库 disable 掉，**不会**回退到后面的 google()/mavenCentral() ——
// 表现为 "Could not resolve com.android.tools:repository"，看着像依赖写错了，其实是镜像抽风。
//
// 所以：
//   - 默认（国内开发）：阿里云优先，官方源兜底
//   - CI：官方源优先，阿里云兜底
//     GitHub runner 在美国，走国内镜像又慢又不稳（已经吃过一次 502 的亏）。
//
// 用法（两种写法都认）：
//   ./gradlew :app:assembleDebug -DmollypawMirror=official
//   ./gradlew :app:assembleDebug -PmollypawMirror=official
//
// 注意：pluginManagement 块是**独立编译**的，作用域里看不到文件顶层的函数或变量
// （写在外面会报 "Unresolved reference"）。所以判断逻辑只能在两个块里各写一份。

pluginManagement {
    repositories {
        val jvm = System.getProperty("mollypawMirror")
        val prop = providers.gradleProperty("mollypawMirror").orNull
        val mode = jvm ?: prop ?: "cn"
        if (mode == "official") {
            google()
            mavenCentral()
            gradlePluginPortal()
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        } else {
            // 阿里云镜像放前面 —— 国内直连 Google/Maven 基本拉不动
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val jvm = System.getProperty("mollypawMirror")
        val prop = providers.gradleProperty("mollypawMirror").orNull
        val mode = jvm ?: prop ?: "cn"
        // 本地仓库优先：bouncycastle 的 jar 是从 Gradle 自带的库里拷过来的
        // （见 tools/setup_local_deps.py），避免为了一个 8MB 的包走网络。
        mavenLocal()
        if (mode == "official") {
            google()
            mavenCentral()
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        } else {
            // 阿里云镜像放前面 —— 国内直连 Google/Maven 基本拉不动
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "MollyPawRemote"
include(":app")
