package life.fxs.purr.core.common

interface PurrLogger {
    fun d(tag: String, message: String)
    fun e(tag: String, throwable: Throwable? = null, message: String)
}

object NoOpPurrLogger : PurrLogger {
    override fun d(tag: String, message: String) = Unit

    override fun e(tag: String, throwable: Throwable?, message: String) = Unit
}
