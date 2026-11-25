pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
// The project lives on a drive shared by Windows and Linux, but local.properties can name only one
// SDK. If its sdk.dir doesn't exist on this OS, point it at this OS's SDK before the build reads it.
run {
    val props = file("local.properties")
    val lines = if (props.exists()) props.readLines() else emptyList()
    val current = lines.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")
        ?.replace("\\:", ":")?.replace("\\\\", "/")
    if (current != null && File(current).isDirectory) return@run
    val sdk = listOfNotNull(
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
        System.getenv("LOCALAPPDATA")?.let { "$it/Android/Sdk" },   // Windows (Android Studio default)
        System.getProperty("user.home") + "/Android/Sdk",           // Linux
    ).map { File(it) }.firstOrNull { it.isDirectory } ?: return@run
    props.writeText((lines.filterNot { it.startsWith("sdk.dir=") } + "sdk.dir=${sdk.path.replace('\\', '/')}").joinToString("\n") + "\n")
}

rootProject.name = "ExpenseTracker"
include(":app")
