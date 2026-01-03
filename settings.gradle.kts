pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://artifactory.appodeal.com/appodeal-public")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://artifactory.appodeal.com/appodeal-public")
    }
}
rootProject.name = "PasiflonetMobile"
include(":app")
