plugins {
    java
    id("com.gradleup.shadow") version "9.0.0"
}

group = "org.betonquest"
version = "2.2.1-SNAPSHOT"
description = "All Your Adventure Supplies"

val betonquestVersion = "2.2.1-DEV-UNOFFICIAL"
val minecraftApiVersion = "1.18"
val pluginDisplayName = "BetonQuest"
val organizationName = "BetonQuest Organisation"
val organizationUrl = "https://www.betonquest.org"
val relocatePrefix = "org.betonquest.betonquest.dependencies"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// BetonQuest still emits Java 17 bytecode. The workspace-wide Paper substitution targets the
// Java 21-only ASPaper API, so compile this compatibility plugin against its declared Paper API.
configurations.all {
    resolutionStrategy.useGlobalDependencySubstitutionRules = false
    resolutionStrategy.dependencySubstitution.all {
        val request = requested
        if (request is org.gradle.api.artifacts.component.ProjectComponentSelector
            && request.projectPath in setOf(":paper-api", ":aspaper-api")) {
            useTarget("io.papermc.paper:paper-api:1.18.2-R0.1-SNAPSHOT", "Keep BetonQuest Java 17 compatible")
        }
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://nexus.hc.to/content/repositories/pub_releases/")
    maven("https://mvn.lumine.io/repository/maven-public/")
    maven("https://nexus.phoenixdevt.fr/repository/maven-public/")
    maven("https://maven.citizensnpcs.co/repo")
    maven("https://repo.codemc.io/repository/maven-public/")
    maven("https://repo.helpch.at/releases/")
    maven("https://repo.dmulloy2.net/nexus/repository/public/")
    maven("https://repo.projectshard.dev/repository/releases/")
    maven("https://maven.elmakers.com/repository/")
    maven("https://jitpack.io")
    maven("https://oss.sonatype.org/content/repositories/releases/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://ci.mg-dev.eu/plugin/repository/everything")
    maven("https://libraries.minecraft.net/")
    maven("https://nexus.betonquest.org/repository/betonquest/")
    maven("https://repo.betonquest.org/betonquest/")
    mavenLocal()
}

dependencies {
    // Compile-time annotations (from parent POM)
    compileOnly("org.jetbrains:annotations:26.1.0")
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.9.8")

    // Paper
    compileOnly("io.papermc.paper:paper-api:1.18.2-R0.1-SNAPSHOT") {
        exclude(group = "junit", module = "junit")
    }
    implementation("io.papermc:paperlib:1.0.8")

    // WorldGuard / WorldEdit
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.9") {
        exclude(group = "org.spigotmc", module = "spigot-api")
    }
    compileOnly("com.sk89q.worldguard:worldguard-core:7.0.9")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.0") {
        exclude(group = "org.spigotmc", module = "spigot-api")
    }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.3.0")

    // Soft-depend plugins (optional integrations)
    compileOnly("com.herocraftonline.heroes:Heroes:1.10.7-RELEASE")
    compileOnly("io.lumine:Mythic-Dist:5.7.2")
    compileOnly("io.lumine:MythicLib-dist:1.7.1-SNAPSHOT")
    compileOnly("net.Indyuce:MMOCore-API:1.12-SNAPSHOT")
    compileOnly("net.Indyuce:MMOItems-API:6.9.4-SNAPSHOT")
    compileOnly("net.citizensnpcs:citizens-main:2.0.33-SNAPSHOT") {
        exclude(group = "junit", module = "junit")
    }
    compileOnly("com.denizenscript:denizen:1.2.5-SNAPSHOT")
    compileOnly("me.filoghost.holographicdisplays:holographicdisplays-api:3.0.5")
    compileOnly("com.gmail.nossr50.mcMMO:mcMMO:2.1.227")
    compileOnly("me.pikamug.quests:quests-core:5.1.1")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("com.comphenix.protocol:ProtocolLib:5.3.0") {
        exclude(group = "net.bytebuddy", module = "byte-buddy")
    }
    compileOnly("com.nisovin.shopkeepers:ShopkeepersAPI:2.22.1")
    compileOnly("com.elmakers.mine.bukkit:MagicAPI:10.2")
    compileOnly("com.bergerkiller.bukkit:TrainCarts:1.20.6-v1")
    compileOnly("com.github.toddharrison.BriarCode:fake-block-api:v2.0.0")
    compileOnly("com.github.Emibergo02:RedisChat:5.3")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    // Align with Skyblock_1218 runtime Skript-2.15.2 (Expression.check uses Predicate, not Checker).
    // Prefer Maven/JitPack coordinates; fall back to the deployed server jar if unresolved.
    compileOnly("com.github.SkriptLang:Skript:2.15.2") {
        exclude(group = "com.google.code.findbugs")
    }
    compileOnly("com.github.DieReicheErethons:Brewery:3.1.1")
    compileOnly("com.github.decentsoftware-eu:decentholograms:2.8.11")
    compileOnly("com.elmakers.mine.bukkit:EffectLib:10.3")
    compileOnly("net.luckperms:api:5.4")
    compileOnly("dev.aurelium:auraskills-api-bukkit:2.2.0")
    compileOnly("studio.magemonkey:fabled:1.0.2-R1")
    compileOnly("com.mojang:authlib:5.0.47")
    compileOnly("org.apache.logging.log4j:log4j-api:3.0.0-beta2")
    compileOnly("org.apache.logging.log4j:log4j-core:3.0.0-beta2")

    // Bundled / relocated (matches maven-shade-plugin includes)
    implementation("org.bstats:bstats-bukkit:3.0.2")
    implementation("com.comphenix.packetwrapper:PacketWrapper:1.13-R0.1-SNAPSHOT")
    implementation("net.kyori:adventure-api:4.17.0")
    implementation("net.kyori:adventure-platform-bukkit:4.3.3")
    implementation("net.kyori:adventure-text-serializer-plain:4.17.0")
    implementation("org.apache.maven:maven-artifact:4.0.0-beta-3") {
        exclude(group = "org.apache.commons", module = "commons-lang3")
    }
    implementation("com.google.guava:guava:33.2.1-jre")
    implementation("commons-io:commons-io:2.16.1")
    implementation("org.apache.commons:commons-lang3:3.15.0")
    implementation("org.json:json:20240303")
    implementation("com.cronutils:cron-utils:9.2.1")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Tests are optional for the deploy pipeline (equivalent to Maven -DskipTests).
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.46.1.3")
    testImplementation("io.papermc.paper:paper-api:1.18.2-R0.1-SNAPSHOT") {
        exclude(group = "junit", module = "junit")
    }
    testCompileOnly("org.jetbrains:annotations:26.1.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
    options.compilerArgs.add("-Xlint:none")
}

// Source trees removed in EaseCation fork when BQ nexus / PacketWrapper are unavailable.
// Keep exclude patterns so a partial restore of those files still builds.
sourceSets {
    main {
        java {
            exclude("**/jobsreborn/**")
            exclude("**/protocollib/FreezeEvent.java")
            exclude("**/protocollib/conversation/MenuConvIO.java")
            exclude("**/protocollib/wrappers/**")
            exclude("**/notify/TotemNotifyIO.java")
        }
    }
}

tasks.processResources {
    // Groovy SimpleTemplateEngine resolves nested map paths for ${project.name} etc.
    val expandProps = mapOf(
        "project" to mapOf(
            "name" to pluginDisplayName,
            "groupId" to project.group.toString(),
            "artifactId" to "betonquest",
            "description" to project.description.orEmpty(),
            "organization" to mapOf(
                "url" to organizationUrl,
                "name" to organizationName,
            ),
        ),
        "betonquest" to mapOf("version" to betonquestVersion),
        "minecraft" to mapOf("api" to mapOf("version" to minecraftApiVersion)),
    )
    filesMatching("plugin.yml") {
        expand(expandProps)
    }
    inputs.property("betonquestVersion", betonquestVersion)
    inputs.property("minecraftApiVersion", minecraftApiVersion)
}

tasks.jar {
    // Plain jar is not the deployable artifact; shadowJar is.
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveFileName.set("BetonQuest.jar")
    archiveClassifier.set("")
    // Project resources (plugin.yml) are added first; keep them if a dependency also ships one.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // Only bundle the same set as maven-shade-plugin includes
    dependencies {
        include(dependency("org.bstats:.*"))
        include(dependency("org.apache.maven:.*"))
        include(dependency("org.apache.commons:.*"))
        include(dependency("com.google.guava:.*"))
        include(dependency("commons-io:.*"))
        include(dependency("org.json:.*"))
        include(dependency("net.kyori:.*"))
        include(dependency("com.comphenix.packetwrapper:.*"))
        include(dependency("io.papermc:paperlib"))
        include(dependency("com.cronutils:cron-utils"))
        include(dependency("com.zaxxer:HikariCP"))
        // transitive pieces commonly pulled by the above
        include(dependency("com.google.guava:failureaccess"))
        include(dependency("com.google.guava:listenablefuture"))
        include(dependency("com.google.errorprone:error_prone_annotations"))
        include(dependency("com.google.j2objc:j2objc-annotations"))
        include(dependency("org.checkerframework:checker-qual"))
        include(dependency("org.slf4j:slf4j-api"))
        include(dependency("org.codehaus.plexus:.*"))
    }

    exclude("META-INF/maven/**")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("classpath.index")

    relocate("org.bstats", "$relocatePrefix.org.bstats")
    relocate("org.apache.maven", "$relocatePrefix.org.apache.maven")
    relocate("org.apache.commons", "$relocatePrefix.org.apache.commons")
    relocate("com.google.common", "$relocatePrefix.com.google.common")
    relocate("org.json", "$relocatePrefix.org.json")
    relocate("net.kyori", "$relocatePrefix.net.kyori")
    relocate("com.comphenix.packetwrapper", "$relocatePrefix.com.comphenix.packetwrapper")
    relocate("io.papermc.lib", "$relocatePrefix.io.papermc.lib")
    relocate("com.cronutils", "$relocatePrefix.com.cronutils")
    relocate("com.zaxxer.hikari", "$relocatePrefix.com.zaxxer.hikari")

    mergeServiceFiles()
}

// Deploy builds match historical Maven -DskipTests.
// Enable with: ./gradlew :BetonQuest:test -Pbetonquest.runTests=true
val runTests = providers.gradleProperty("betonquest.runTests")
    .map { it.toBoolean() }
    .orElse(false)

tasks.test {
    useJUnitPlatform()
    onlyIf { runTests.get() }
}

tasks.compileTestJava {
    onlyIf { runTests.get() }
}

tasks.processTestResources {
    onlyIf { runTests.get() }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// Default assemble should produce the deployable shaded jar
tasks.assemble {
    dependsOn(tasks.shadowJar)
}
