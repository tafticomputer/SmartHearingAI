package ir.smarthearing.ai

/** پوشش JNI برای RNNoise (شبکه عصبی حذف نویز). فریم: ۴۸۰ نمونه در ۴۸ کیلوهرتز. */
object RNNoise {
    const val FRAME = 480

    val available: Boolean = try {
        System.loadLibrary("rnnoise_jni")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    external fun create(): Long
    external fun destroy(handle: Long)
    external fun process(handle: Long, input: FloatArray, output: FloatArray): Float
}
