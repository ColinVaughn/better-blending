// Central Stonecutter script: applied to every version node. One node is one
// (Minecraft version, loader) pair, named "<minecraft>-<loader>".

plugins {
    java
    pmd
    id("dev.architectury.loom") version "1.17.493" apply false
    id("dev.architectury.loom-no-remap") version "1.17.493" apply false
    id("dev.kikugie.stonecutter")
}

val loader = stonecutter.current.project.substringAfterLast('-')
val minecraftVersion = stonecutter.current.version
val isPrimary = stonecutter.current.project == "1.21.1-fabric"

// Minecraft ships unobfuscated from 26.1. Those nodes have nothing to remap: no
// mappings, no refmap, and mods sit on the plain configurations instead of mod*.
val unobfuscated = stonecutter.eval(minecraftVersion, ">=26.1")
// The 1.21.5 render rewrite replaced loose shader uniforms with uniform blocks.
val looseUniforms = stonecutter.eval(minecraftVersion, "<1.21.5")
apply(plugin = if (unobfuscated) "dev.architectury.loom-no-remap" else "dev.architectury.loom")
val loom = extensions.getByType<net.fabricmc.loom.api.LoomGradleExtensionAPI>()
fun modConfiguration(name: String) =
    if (unobfuscated) name else "mod" + name.replaceFirstChar(Char::uppercaseChar)

// Node properties live in versions/<node>/gradle.properties. Gradle exposes those
// through findProperty only; providers.gradleProperty sees root properties alone.
fun propOrNull(name: String): String? = project.findProperty(name)?.toString()
fun prop(name: String): String = propOrNull(name)
    ?: error("Missing property '$name' for node ${stonecutter.current.project}")

group = "dev.betterblending"
version = rootProject.property("mod.version").toString()
base { archivesName.set("better-blending-$minecraftVersion-$loader") }

// Loader and Minecraft version are both available to //? if directives.
stonecutter.constants {
    put("fabric", loader == "fabric")
    put("forge", loader == "forge")
    put("neoforge", loader == "neoforge")
    // "modloader" covers the two Forge-family loaders, which share most API shapes.
    put("forgelike", loader == "forge" || loader == "neoforge")
}

// Pure renames go here rather than into directives; the checked-in tree keeps the
// primary node's spelling. A false direction rewrites the other way, so the new name
// must not appear as a whole word in shared sources for any other reason.
stonecutter.replacements {
    regex(unobfuscated) { // Renamed in 26.1.
        replace("\\bResourceLocation\\b", "Identifier", "\\bIdentifier\\b", "ResourceLocation")
    }
}

val javaVersion = prop("java.version").toInt()
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
    withSourcesJar()
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(javaVersion)
}

// src/main is the shared, preprocessed tree. Each node also names, in era.sources, the
// directories holding code specific to its era; several versions of one era can share
// a directory. Those are compiled but not preprocessed, which suits code that is
// wholly specific to an era. A "<dir>-<loader>" directory is picked up alongside each.
val eraSources = prop("era.sources").split(',').map(String::trim)
sourceSets {
    named("main") {
        java.srcDir(rootProject.file("src/$loader/java"))
        resources.srcDir(rootProject.file("src/$loader/resources"))
        for (dir in eraSources.flatMap { listOf(it, "$it-$loader") }) {
            val java = rootProject.file("src/$dir/java")
            val resources = rootProject.file("src/$dir/resources")
            if (java.isDirectory) this.java.srcDir(java)
            if (resources.isDirectory) this.resources.srcDir(resources)
        }
    }
}

// The first of this node's era directories that holds the named file.
fun eraFile(path: String): File = eraSources.map { rootProject.file("src/$it/$path") }.firstOrNull(File::isFile)
    ?: error("No era directory of ${stonecutter.current.project} has $path (era.sources=$eraSources)")

// `gradlew "<node>:runClient" -PauditMixins` applies every mixin at startup, then exits
// with a failure if any required injection no longer matches. See MixinAudit.
if (propOrNull("auditMixins") != null) {
    loom.runs.configureEach { vmArg("-Dbetter_blending.auditMixins=true") }
}
// `-PsmokeTest` on a 26.x node drives the GPU path through the real device, then exits.
// It opens the game window, so unlike the audit it needs a GPU. See ModernSmokeTest.
if (propOrNull("smokeTest") != null) {
    loom.runs.configureEach { vmArg("-Dbetter_blending.smokeTest=true") }
}
// When Gradle itself runs on Java 19 or newer, Loom moves 1.20.1 development runs to
// LWJGL 3.3.2, and Sodium 0.5 refuses to start on anything but the 3.3.1 Minecraft
// ships. Loom chose that version, not a player's launcher, so the check is off in dev.
if (stonecutter.eval(minecraftVersion, "<1.20.2")) {
    loom.runs.configureEach { vmArg("-Dsodium.checks.issue2561=false") }
}

if (!unobfuscated) {
    loom.silentMojangMappingsLicense()
    loom.mixin.useLegacyMixinAp.set(true)
    loom.mixin.add(sourceSets.main.get(), "better_blending.refmap.json")
}
// Forge before NeoForge reads mixin configs from the jar manifest, not mods.toml.
if (loader == "forge") loom.forge.mixinConfigs("better_blending.mixins.json", "better_blending.compat.mixins.json")

repositories {
    maven("https://api.modrinth.com/maven")
    maven("https://maven.neoforged.net/releases/")
    maven("https://maven.shedaniel.me/")
    maven("https://maven.terraformersmc.com/releases/")
}

// Another node's property, for the primary node's tests of shaders shared across eras.
fun nodeProp(node: String, name: String): String =
    rootProject.file("versions/$node/gradle.properties").readLines()
        .firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.trim()
        ?: error("Missing property '$name' for node $node")

// Upstream renderer builds whose terrain shaders the tests adapt, by fixture name.
// The GL-era patch also serves 1.20.1's renderers, so their shaders are tested here too.
// Several builds keep Sodium's asset namespace, so each is its own jar, found by name.
val rendererFixtures = if (!isPrimary) emptyMap() else mapOf(
    "sodium" to "maven.modrinth:sodium:${prop("sodium.version")}",
    "embeddium" to "maven.modrinth:embeddium:${prop("embeddium.version")}",
    "sodium-0.5" to "maven.modrinth:sodium:${nodeProp("1.20.1-fabric", "sodium.version")}",
    "embeddium-0.3" to "maven.modrinth:embeddium:${nodeProp("1.20.1-forge", "embeddium.version")}",
    "rubidium" to "maven.modrinth:rubidium:${nodeProp("1.20.1-forge", "rubidium.version")}",
).mapValues { (_, notation) ->
    configurations.detachedConfiguration(dependencies.create(notation)).apply { isTransitive = false }
}

// Some releases ship as a thin wrapper holding the real mod as a nested jar, which the
// compiler cannot read. This extracts any nested jars so they can be compiled against.
fun nestedJars(notation: String): FileCollection {
    val wrapper = configurations.detachedConfiguration(dependencies.create(notation)).apply { isTransitive = false }
    val name = notation.substringAfter(':').substringBefore(':')
    val unwrap = tasks.register<Sync>("unwrap${name.replaceFirstChar { it.uppercase() }}") {
        from(wrapper.elements.map { files -> files.map { zipTree(it.asFile) } }) {
            include("META-INF/jarjar/*.jar")
            eachFile { path = this.name }
        }
        includeEmptyDirs = false
        into(layout.buildDirectory.dir("nested/$name"))
    }
    return files(unwrap).asFileTree
}

dependencies {
    "minecraft"("com.mojang:minecraft:$minecraftVersion")
    if (!unobfuscated) "mappings"(loom.officialMojangMappings())
    val modImplementation = modConfiguration("implementation")
    val modCompileOnly = modConfiguration("compileOnly")
    val modRuntimeOnly = modConfiguration("runtimeOnly")

    val sodium = propOrNull("sodium.version")
    val iris = propOrNull("iris.version")
    val irisCompile = iris ?: propOrNull("iris.compile.version")
    // Iris is published as Oculus for Forge; the API packages are the same.
    val irisSlug = propOrNull("iris.slug") ?: "iris"
    val embeddium = propOrNull("embeddium.version")
    val glslTransformer = prop("glsl.transformer.version")

    compileOnly("io.github.douira:glsl-transformer:$glslTransformer")
    if (sodium != null) {
        modCompileOnly("maven.modrinth:sodium:$sodium")
        // Sodium for NeoForge on 26.x is such a wrapper.
        if (loader == "neoforge") compileOnly(nestedJars("maven.modrinth:sodium:$sodium"))
    }
    if (irisCompile != null) modCompileOnly("maven.modrinth:$irisSlug:$irisCompile")
    if (embeddium != null) modCompileOnly("maven.modrinth:embeddium:$embeddium")

    when (loader) {
        "fabric" -> {
            modImplementation("net.fabricmc:fabric-loader:${prop("loader.version")}")
            modImplementation("net.fabricmc.fabric-api:fabric-api:${prop("fabric.version")}")
            modImplementation("me.shedaniel.cloth:cloth-config-fabric:${prop("cloth.version")}") {
                exclude(group = "net.fabricmc.fabric-api")
            }
            modImplementation("com.terraformersmc:modmenu:${prop("modmenu.version")}")
        }
        "neoforge" -> {
            "neoForge"("net.neoforged:neoforge:${prop("neoforge.version")}")
            modImplementation("me.shedaniel.cloth:cloth-config-neoforge:${prop("cloth.version")}")
        }
        "forge" -> {
            "forge"("net.minecraftforge:forge:${prop("forge.version")}")
            modImplementation("me.shedaniel.cloth:cloth-config-forge:${prop("cloth.version")}")
            // Fabric Loader and NeoForge ship MixinExtras; Forge does not, so bundle it.
            // The processor matters too: Forge runs on SRG names, so the refmap needs
            // entries for MixinExtras injector targets as well as for Mixin's own.
            val mixinExtras = prop("mixinextras.version")
            "annotationProcessor"("io.github.llamalad7:mixinextras-common:$mixinExtras")
            compileOnly("io.github.llamalad7:mixinextras-common:$mixinExtras")
            "include"("io.github.llamalad7:mixinextras-forge:$mixinExtras")
            // mixinextras-forge only wraps the real classes as a nested jar, which Forge
            // unpacks in production but not in a development launch; add them directly.
            "forgeRuntimeLibrary"("io.github.llamalad7:mixinextras-forge:$mixinExtras")
            "forgeRuntimeLibrary"("io.github.llamalad7:mixinextras-common:$mixinExtras")
        }
    }

    // Selecting a renderer at runtime is a local debugging aid, not a build input.
    // -Prenderer=sodium|embeddium|rubidium picks one; -Piris adds Iris (Oculus on Forge)
    // with the Sodium-family renderer it runs on: Sodium, or Embeddium where there is none.
    val withIris = propOrNull("iris") != null
    val irisHost = if (sodium != null) "sodium" else "embeddium"
    val renderer = propOrNull("renderer") ?: if (withIris) irisHost else null
    // Every node is configured, but only the launched one needs its renderer, so a
    // choice a node cannot run fails that node's launch rather than the whole build.
    val unsupported = when {
        withIris && iris == null -> "No Iris for ${stonecutter.current.project}"
        withIris && renderer != irisHost -> "Iris on ${stonecutter.current.project} runs on $irisHost"
        renderer != null && propOrNull("$renderer.version") == null -> "No $renderer for ${stonecutter.current.project}"
        else -> null
    }
    if (unsupported != null) {
        tasks.matching { it.name == "runClient" }.configureEach { doFirst { throw GradleException(unsupported) } }
    }
    // Fabric and Forge publish jars under production names, which Loom remaps for a
    // development launch. NeoForge's and 26.x's run as published, which also keeps
    // Sodium's bootstrap service and Iris's bundled libraries discoverable. Embeddium
    // is the exception: one of its mixins names a Minecraft lambda by its production
    // name, and Loom's development Minecraft jar names lambdas differently, so on
    // NeoForge Embeddium is remapped as well.
    fun runtimeMod(slug: String) = if (loader == "neoforge" && slug != "embeddium") "runtimeOnly" else modRuntimeOnly
    if (unsupported == null && renderer != null) {
        runtimeMod(renderer)("maven.modrinth:$renderer:${prop("$renderer.version")}")
    }
    if (unsupported == null && withIris) {
        runtimeMod(irisSlug)("maven.modrinth:$irisSlug:$iris")
        // A remapped development jar leaves out the shader compiler libraries Iris bundles.
        when (loader) {
            "fabric" -> {
                runtimeOnly("org.anarres:jcpp:1.4.14")
                runtimeOnly("io.github.douira:glsl-transformer:$glslTransformer")
            }
            "forge" -> "forgeRuntimeLibrary"("org.anarres:jcpp:1.4.14")
        }
    }

    if (isPrimary) {
        testImplementation("io.github.douira:glsl-transformer:$glslTransformer")
        testImplementation("net.fabricmc:fabric-loader-junit:${prop("loader.version")}")
    }
}

// The suite exercises GLSL and GPU behaviour that does not vary by loader, so it
// runs on the primary node only; CI covers the other nodes with the mixin audit.
// Stonecutter wires src/test into every node by itself, so the other nodes have to
// have it taken away again; otherwise they compile the suite without JUnit present.
if (isPrimary) {
    tasks.test {
        useJUnitPlatform()
        for (fixture in rendererFixtures.values) inputs.files(fixture)
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            rendererFixtures.map { (name, fixture) -> "-Dbb.rendererFixture.$name=${fixture.singleFile}" }
        })
        for (flag in listOf("BB_SHADER_GL_TEST", "BB_SHADER_BENCHMARK", "BB_TERRAIN_PROFILE", "BB_SHADER_AUDIT", "BB_AUDIT_MAX_CHANGED")) {
            inputs.property(flag, providers.environmentVariable(flag).orElse("0"))
        }
    }
} else {
    sourceSets.named("test") {
        java.setSrcDirs(emptyList<File>())
        resources.setSrcDirs(emptyList<File>())
    }
    tasks.test { enabled = false }
}

// The blending algorithm lives once in terrain_core.glsl. Each era supplies the
// prologue that binds it to that era's uniforms and fog, plus the entry point.
val generatedShaders = layout.buildDirectory.dir("generated/shaders")
val composeTerrainShader = tasks.register("composeTerrainShader") {
    val shaders = rootProject.file("src/main/resources/assets/better_blending/shaders/core")
    val core = shaders.resolve("terrain_core.glsl")
    val uniforms = shaders.resolve("terrain_uniforms.glsl")
    val prologue = eraFile("glsl/terrain_prologue.glsl")
    val epilogue = eraFile("glsl/terrain_epilogue.glsl")
    val target = generatedShaders.map { it.file("assets/better_blending/shaders/core/terrain.fsh") }
    inputs.files(core, uniforms, prologue, epilogue)
    inputs.property("looseUniforms", looseUniforms)
    // An output directory, so resources.srcDir below also carries the task dependency
    // to every consumer of the resources (processResources and sourcesJar alike).
    outputs.dir(generatedShaders)
    doLast {
        val file = target.get().asFile
        file.parentFile.mkdirs()
        val declared = if (looseUniforms) uniforms.readText() + "\n" else ""
        file.writeText(prologue.readText() + "\n" + declared + core.readText() + epilogue.readText())
    }
}
sourceSets.named("main") { resources.srcDir(composeTerrainShader) }

tasks.processResources {
    val tokens = mapOf(
        "version" to project.version.toString(),
        "minecraft" to prop("deps.minecraft"),
        "minecraftRange" to propOrNull("deps.minecraftRange").orEmpty(),
        "loaderRange" to propOrNull("deps.loaderRange").orEmpty(),
        "loader" to propOrNull("loader.version").orEmpty(),
        "java" to javaVersion.toString(),
        "clothRange" to propOrNull("deps.clothRange").orEmpty(),
        "modmenuRange" to propOrNull("deps.modmenuRange").orEmpty(),
    )
    tokens.forEach { (key, value) -> inputs.property(key, value) }
    filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
        expand(tokens)
    }
}

// The backend package must never name a Minecraft type, and the shared GLSL core must
// stay era-neutral. Breaking either goes unnoticed until another era is added.
val checkVersionFreeSources = tasks.register("checkVersionFreeSources") {
    group = "verification"
    description = "Fails if the version-free backend or shared GLSL gains an era dependency."
    val backend = rootProject.file("src/main/java/dev/betterblending/backend")
    val shaderCore = rootProject.file("src/main/resources/assets/better_blending/shaders/core/terrain_core.glsl")
    val root = rootProject.projectDir
    val stamp = layout.buildDirectory.file("tmp/checkVersionFreeSources.stamp")
    inputs.dir(backend)
    inputs.file(shaderCore)
    outputs.file(stamp)
    doLast {
        val problems = mutableListOf<String>()
        val era = Regex("""^import\s+(?:static\s+)?(net\.minecraft|com\.mojang|org\.lwjgl)\S*""", RegexOption.MULTILINE)
        backend.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { file ->
            era.findAll(file.readText()).forEach {
                problems += "${file.relativeTo(root).invariantSeparatorsPath}: ${it.value.trim()}"
            }
        }
        val core = shaderCore.readText()
        for (token in listOf("#version", "#moj_import", "void main(")) {
            if (core.contains(token)) problems += "terrain_core.glsl is era-neutral but contains '$token'"
        }
        // Samplers bind the same way in every era; everything else is loose in some eras
        // and a uniform block in others, so it must be declared outside the core.
        Regex("""^uniform\s+(?!sampler)\S+\s+(\w+)""", RegexOption.MULTILINE).findAll(core).forEach {
            problems += "terrain_core.glsl declares uniform storage for '${it.groupValues[1]}'"
        }
        if (problems.isNotEmpty()) {
            throw GradleException(problems.joinToString(
                separator = "\n  ",
                prefix = "Version-free sources must not depend on one Minecraft era:\n  "))
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok")
    }
}
tasks.named("check") { dependsOn(checkVersionFreeSources) }

// Complexity limits on production code. `check` runs them on this node's sources, so
// each node's CI build covers its own era code. The GPU scenario tests are long by
// design and stay out of it. Reports land in build/reports/pmd.
pmd {
    toolVersion = "7.27.0"
    ruleSets = emptyList()
    ruleSetFiles = files(rootProject.file("config/pmd/complexity.xml"))
    isConsoleOutput = true
}
tasks.named("pmdTest") { enabled = false }

tasks.withType<Jar>().configureEach { from(rootProject.file("LICENSE")) }
