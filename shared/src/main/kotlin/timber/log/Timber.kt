package timber.log

/**
 * JVM stand-in for Android's Timber so the shared innertube facade compiles on
 * desktop. On Android the real `libs.timber` artifact provides this class; the
 * two never share a classpath. Calls are no-ops for now — desktop logging gets
 * an slf4j backend when the player MVP lands.
 */
object Timber {
    @JvmStatic
    fun d(message: String?, vararg args: Any?) {}

    @JvmStatic
    fun w(message: String?, vararg args: Any?) {}

    @JvmStatic
    fun e(message: String?, vararg args: Any?) {}

    @JvmStatic
    fun e(t: Throwable?, message: String?, vararg args: Any?) {}

    @JvmStatic
    fun w(t: Throwable?, message: String?, vararg args: Any?) {}
}
