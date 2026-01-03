pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://artifactory.appodeal.com/appodeal-public")
        flatDir { dirs("app/libs") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://artifactory.appodeal.com/appodeal-public")
        flatDir { dirs("app/libs") }
    }
}
rootProject.name = "PasiflonetMobile"
include(":app")
