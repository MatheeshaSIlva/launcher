package dev.launcher.engine.expr

/**
 * What an expression reads, each with the offset of its first use: the bindings re-evaluated when one of them changes
 * (state keys, prop names, token keys, source paths, and the clock).
 */
data class Deps(
    val states: Map<String, Int>,
    val props: Map<String, Int>,
    val tokens: Map<String, Int>,
    val sources: Map<String, Int>,
    val clock: Boolean,
) {
    companion object {
        /** [props]: the component's prop names (an `@name` that is not one is a token). */
        fun of(t: Template, props: Set<String>): Deps {
            val states = LinkedHashMap<String, Int>(); val ps = LinkedHashMap<String, Int>()
            val tokens = LinkedHashMap<String, Int>(); val sources = LinkedHashMap<String, Int>()
            var clock = false
            fun walk(n: Node) {
                when (n) {
                    is Node.State -> states.putIfAbsent(n.name, n.at)
                    is Node.Ref -> (if (n.name in props) ps else tokens).putIfAbsent(n.name, n.at)
                    is Node.Path -> if (n.path == "now") clock = true else sources.putIfAbsent(n.path, n.at)
                    is Node.Unary -> walk(n.operand)
                    is Node.Binary -> { walk(n.left); walk(n.right) }
                    is Node.Cond -> { walk(n.test); walk(n.then); walk(n.otherwise) }
                    is Node.Pipe -> walk(n.value)
                    is Node.Num, is Node.Str, is Node.Bool, is Node.Null -> {}
                }
            }
            t.holes.forEach(::walk)
            return Deps(states, ps, tokens, sources, clock)
        }
    }
}
