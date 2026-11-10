package dev.logisim.mobile.core

internal object TtlPal {
    private val p74181 = listOf(
        intArrayOf(0x00008011, 0x00004101),
        intArrayOf(0x00008022, 0x00004202),
        intArrayOf(0x00008044, 0x00004404),
        intArrayOf(0x00008088, 0x00004808),
        intArrayOf(0x00002100, 0x00001010, 0x00000001),
        intArrayOf(0x00002200, 0x00001020, 0x00000002),
        intArrayOf(0x00002400, 0x00001040, 0x00000004),
        intArrayOf(0x00002800, 0x00001080, 0x00000008),
        intArrayOf(0x00000300),
        intArrayOf(0x00000301, 0x00000110),
        intArrayOf(0x00000303, 0x00000112, 0x00000120),
        intArrayOf(0x00000307, 0x00000116, 0x00000124, 0x00000140),
        intArrayOf(0x0000000f),
        intArrayOf(0x00000080, 0x00000048, 0x0000002c, 0x0000001e),
        intArrayOf(0x0000020f),
    )
    private val p74381 = listOf(
        intArrayOf(0x000c1010, 0x00161001, 0x000a0110),
        intArrayOf(0x000c2020, 0x00162002, 0x000a0220),
        intArrayOf(0x000c4040, 0x00164004, 0x000a0440),
        intArrayOf(0x000c8080, 0x00168008, 0x000a0880),
        intArrayOf(0x00131010, 0x000c1001, 0x000c0110, 0x00120101),
        intArrayOf(0x00132020, 0x000c2002, 0x000c0220, 0x00120202),
        intArrayOf(0x00134040, 0x000c4004, 0x000c0440, 0x00120404),
        intArrayOf(0x00138080, 0x000c8008, 0x000c0880, 0x00120808),
        intArrayOf(0x00000300),
        intArrayOf(0x00000301, 0x00000111),
        intArrayOf(0x00000303, 0x00000113, 0x00000122),
        intArrayOf(0x00000307, 0x00000117, 0x00000126, 0x00000144),
        intArrayOf(0x0000000f),
        intArrayOf(0x00000088, 0x0000004c, 0x0000002e, 0x0000001f),
    )
    private fun pal(inputs: List<Boolean>, terms: IntArray): Boolean = !terms.any { term ->
        inputs.indices.all { bit -> term and (1 shl bit) == 0 || inputs[bit] }
    }

    fun evaluate(model: String, pins: Map<Int, LogicValue>): TtlResult {
        fun h(pin: Int) = pins.pin(pin) == LogicValue.HIGH
        val out = linkedMapOf<Int, LogicValue>()
        fun set(pin: Int, value: Boolean) { out[pin] = value.ttlValue() }
        when (model) {
            "74181" -> {
                val a=listOf(2,23,21,19).map(::h)
                val b=listOf(1,22,20,18).map(::h)
                val s=listOf(6,5,4,3).map(::h)
                val level1=a+b+b.map { !it }+s
                val x=(0..3).map { pal(level1,p74181[it]) }
                val y=(4..7).map { pal(level1,p74181[it]) }
                val level2=x+y+listOf(!h(8),h(7))
                val z=(8..11).map { pal(level2,p74181[it]) }
                val f=(0..3).map { x[it] xor y[it] xor z[it] }
                set(15,pal(level2,p74181[12]))
                val g=pal(level2,p74181[13]);set(17,g)
                set(16,!pal(level2,p74181[14]) || !g)
                out[14]=if(f.all{it})LogicValue.UNKNOWN else LogicValue.LOW
                for((i,pin)in listOf(9,10,11,13).withIndex())set(pin,f[i])
            }
            "74381" -> {
                val a=listOf(3,1,19,17).map(::h)
                val b=listOf(4,2,18,16).map(::h)
                val s=listOf(5,6,7).map(::h)
                val u=s[0]||s[1];val v=s[1]||s[2];val w=s[0]||!s[1]
                val x=!(s[0]&&s[1])||s[2];val y=(s[0]&&s[1])||!s[2];val z=!s[2]&&u
                val level1=a+a.map{!it}+b+b.map{!it}+listOf(y,x,w,v,u)
                val j=(0..3).map{pal(level1,p74381[it])}
                val k=(4..7).map{pal(level1,p74381[it])}
                val level2=j+k+listOf(z,h(15))
                val l=(8..11).map{pal(level2,p74381[it])}
                set(14,pal(level2,p74381[12]));set(13,pal(level2,p74381[13]))
                for((i,pin)in listOf(8,9,11,12).withIndex())set(pin,l[i] xor k[i])
            }
            "74182" -> {
                val p=listOf(4,2,15,6).map{!h(it)};val g=listOf(3,1,14,5).map{!h(it)};val ci=h(13)
                set(7,!p.all{it})
                set(10,!(g[3] || p[3]&&g[2] || p[3]&&p[2]&&g[1] || p[3]&&p[2]&&p[1]&&g[0]))
                set(12,g[0] || p[0]&&ci)
                set(11,g[1] || p[1]&&g[0] || p[1]&&p[0]&&ci)
                set(9,g[2] || p[2]&&g[1] || p[2]&&p[1]&&g[0] || p[2]&&p[1]&&p[0]&&ci)
            }
            else -> error("PAL TTL desconhecido: $model")
        }
        return TtlResult(out)
    }
}
