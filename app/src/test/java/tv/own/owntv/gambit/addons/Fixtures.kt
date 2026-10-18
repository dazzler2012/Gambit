package tv.own.owntv.gambit.addons

/** Loads hand-written addon responses from src/test/resources/gambit/addons. No fixture holds a real URL or key. */
object Fixtures {
    fun read(name: String): String {
        val stream = Fixtures::class.java.classLoader!!.getResourceAsStream("gambit/addons/$name")
            ?: error("missing fixture $name")
        return stream.bufferedReader().use { it.readText() }
    }
}
