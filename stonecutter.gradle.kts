plugins {
    id("dev.kikugie.stonecutter")
}

// The version whose form the working tree is checked in as. Stonecutter rewrites
// src/ in place when the active version changes, so switch back to this one before
// committing. Switch with the generated "Set active project to <node>" tasks.
stonecutter active "1.21.1-fabric"

// Stonecutter 0.9 dropped the older chiseled-task helper; aggregate the per-node
// tasks instead. stonecutter.tasks.named maps each node to its own task provider.
fun registerAggregate(name: String, nodeTask: String, summary: String) {
    val perNode = stonecutter.tasks.named(nodeTask)
    tasks.register(name) {
        group = "build"
        description = summary
        dependsOn(perNode.map { it.values.toList() })
    }
}

registerAggregate("buildAll", "build", "Builds every version node.")
registerAggregate("assembleAll", "assemble", "Assembles the jar for every version node.")
