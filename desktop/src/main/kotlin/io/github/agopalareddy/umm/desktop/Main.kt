package io.github.agopalareddy.umm.desktop

import io.github.agopalareddy.umm.core.platform.XdgPaths
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val dictateArgs = parseArgs(args)
    if (dictateArgs == null) {
        System.err.println(USAGE)
        exitProcess(2)
    }
    val graph = DesktopGraph(XdgPaths(), System.getenv())
    val code = runBlocking { dictate(dictateArgs, graph, System.out) }
    graph.close()
    exitProcess(code)
}
