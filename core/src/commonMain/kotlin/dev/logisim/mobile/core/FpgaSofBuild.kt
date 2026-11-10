package dev.logisim.mobile.core

val FpgaBuildPlan.producesQuartusSof: Boolean
    get() = flow == FpgaFlow.QUARTUS && stages.lastOrNull()?.outputFiles?.singleOrNull()?.endsWith(".sof") == true
