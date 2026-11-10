package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FloatingPointExactNanTest {
    private val quiet = 1L shl 51

    @Test fun fusedMultiplyAddPropagatesTheFirstNanLikeTheAArch64Intrinsic() {
        val signaling = Double.fromBits(0x7ff0_0000_0000_1234L)
        val quietNan = Double.fromBits(0x7ff8_0000_0000_5678L)
        // A signaling NaN wins over a quiet one, and the result is quiet with the same payload.
        assertEquals(0x7ff8_0000_0000_1234L, FloatingPointExact.fma(quietNan, 1.0, signaling).toRawBits())
        // Among quiet NaNs, the addend comes first.
        val addend = Double.fromBits(0x7ff8_0000_0000_0042L)
        assertEquals(addend.toRawBits(), FloatingPointExact.fma(quietNan, 1.0, addend).toRawBits())
        assertEquals(quietNan.toRawBits() or quiet, FloatingPointExact.fma(quietNan, 2.0, 3.0).toRawBits())
        // FP16 0x7C4F as addend reaches the output as 0x7E4F, as in Evolution.
        val half = FloatingPointNumbers.decode(LogicVector.of(16, 0x7c4fuL))
        val result = FloatingPointNumbers.encode(16, FloatingPointExact.fma(0.5, 0.25, half))
        assertEquals(0x7e4fuL, result.toULongOrNull())
    }
}
