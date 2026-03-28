pluginManagement {
    repositories {
        // 使用国内镜像，但保留必要的官方仓库作为备用
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 使用国内镜像，但保留官方仓库作为备用
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}
rootProject.name = "KotlinAutoClicker"
include(":app")
// 此配置将Gradle插件和项目依赖的仓库指向阿里云镜像，以解决国内网络环境下下载缓慢的问题。
