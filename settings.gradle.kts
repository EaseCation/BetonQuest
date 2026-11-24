pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "BetonQuest"

includeBuild("../PlayerObserverPlatform") {
    dependencySubstitution {
        substitute(module("net.easecation.playerobserver:observer-api")).using(project(":observer-api"))
    }
}
