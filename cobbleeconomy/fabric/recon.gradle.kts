// TEMPORARY RECON -- delete this file once the signatures are settled.
//
// Reads the Minecraft jar Loom already put on the compile classpath and reports
// what actually exists, rather than what a tutorial written for 1.21 says exists.
//
//   ./gradlew :fabric:recon
//
// Applied from fabric/build.gradle.kts.

tasks.register("recon") {
    group = "help"
    description = "Dumps real 26.2 signatures for the classes this mod depends on."

    val cp = sourceSets["main"].compileClasspath

    doLast {
        val jars = cp.files.filter { it.name.endsWith(".jar") }

        println("=".repeat(70))
        println("CLASSES whose simple name matches")
        println("=".repeat(70))
        val needles = listOf(
            "ResourceLocation", "Identifier", "ResourceKey", "ResourceId",
            "PermissionSource", "Permissions", "PermissionLevel", "CommandSourceStack"
        )
        val seen = sortedSetOf<String>()
        jars.forEach { jar ->
            runCatching {
                java.util.zip.ZipFile(jar).use { zf ->
                    zf.entries().toList()
                        .map { it.name }
                        .filter { it.endsWith(".class") && !it.contains("$") }
                        .map { it.removeSuffix(".class").replace('/', '.') }
                        .filter { n -> needles.any { n.substringAfterLast('.') == it } }
                        .forEach { seen.add(it) }
                }
            }
        }
        seen.forEach { println("  $it") }

        val loader = java.net.URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray())

        fun dump(className: String, filter: (String) -> Boolean) {
            println()
            println("=".repeat(70))
            println("METHODS of $className")
            println("=".repeat(70))
            runCatching {
                val c = loader.loadClass(className)
                println("  implements: " + c.interfaces.joinToString { it.name })
                println("  extends:    " + (c.superclass?.name ?: "-"))
                c.methods
                    .filter { filter(it.name) }
                    .sortedBy { it.name }
                    .forEach {
                        println("  ${it.returnType.simpleName} ${it.name}(" +
                            it.parameterTypes.joinToString { p -> p.simpleName } + ")")
                    }
            }.onFailure { println("  COULD NOT LOAD: $it") }
        }

        // Everything permission-shaped, plus the accessors the commands use.
        dump("net.minecraft.commands.CommandSourceStack") { n ->
            n.contains("ermission", true) || n.contains("evel", true) ||
            n.startsWith("get") || n.startsWith("has") || n.startsWith("send")
        }

        // The registry lookup ItemBank needs.
        dump("net.minecraft.core.Registry") { n ->
            n.contains("get", true) || n.contains("Optional", true)
        }

        // Confirm the inventory accessors and the tick counter.
        dump("net.minecraft.world.entity.player.Inventory") { n ->
            n.contains("Container", true) || n == "getItem" || n == "setItem" || n == "add"
        }
        dump("net.minecraft.server.MinecraftServer") { n -> n.contains("ick", true) }

        println()
        println("Done. Paste everything above.")
    }
}
