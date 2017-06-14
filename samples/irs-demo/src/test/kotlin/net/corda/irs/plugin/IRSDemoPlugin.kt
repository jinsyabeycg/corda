package net.corda.irs.plugin

import net.corda.core.node.CordaPluginRegistry
import net.corda.irs.api.IRSDemoAPI
import java.util.function.Function

class IRSDemoPlugin : CordaPluginRegistry() {
    override val webApis = listOf(Function(::IRSDemoAPI))
}