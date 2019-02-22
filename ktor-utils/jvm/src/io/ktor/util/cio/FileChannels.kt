package io.ktor.util.cio

import io.ktor.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.io.*
import kotlinx.coroutines.io.jvm.nio.*
import kotlinx.io.core.*
import kotlinx.io.pool.*
import java.io.*
import java.nio.*
import java.nio.channels.*
import kotlin.coroutines.*

/**
 * Open a read channel for file and launch a coroutine to fill it.
 * Please note that file reading is blocking so if you are starting it on [Dispatchers.Unconfined] it may block
 * your async code
 */
@KtorExperimentalAPI
@UseExperimental(ExperimentalIoApi::class)
fun File.readChannel(
    start: Long = 0,
    endInclusive: Long = -1,
    coroutineContext: CoroutineContext = Dispatchers.IO
): ByteReadChannel {
    val fileLength = length()
    val file = RandomAccessFile(this@readChannel, "r")
    return CoroutineScope(coroutineContext).writer(coroutineContext, autoFlush = false) {
        require(start >= 0L) { "start position shouldn't be negative but it is $start" }
        require(endInclusive <= fileLength - 1) { "endInclusive points to the position out of the file: file size = ${file.length()}, endInclusive = $endInclusive" }

        file.use {
            val fileChannel: FileChannel = file.channel
            if (start > 0) {
                fileChannel.position(start)
            }

            if (endInclusive == -1L) {
                channel.writeSuspendSession {
                    while (true) {
                        val buffer = request(1)
                        if (buffer == null) {
                            channel.flush()
                            tryAwait(1)
                            continue
                        }

                        val rc = fileChannel.read(buffer)
                        if (rc == -1) break
                        written(rc)
                    }
                }

                return@use
            }

            var position = start
            channel.writeWhile { buffer ->
                val fileRemaining = endInclusive - position + 1
                val rc = if (fileRemaining < buffer.remaining()) {
                    val l = buffer.limit()
                    buffer.limit(buffer.position() + fileRemaining.toInt())
                    val r = fileChannel.read(buffer)
                    buffer.limit(l)
                    r
                } else {
                    fileChannel.read(buffer)
                }

                if (rc > 0) position += rc

                rc != -1 && position <= endInclusive
            }
        }
    }.channel
}

/**
 * Open a write channel for file and launch a coroutine to read from it.
 * Please note that file writing is blocking so if you are starting it on [Dispatchers.Unconfined] it may block
 * your async code
 */
@Deprecated(
    "Pool is not required here anymore so use writeChannel without specifying a pool.",
    ReplaceWith("writeChannel()")
)
fun File.writeChannel(
    @Suppress("UNUSED_PARAMETER") pool: ObjectPool<ByteBuffer>
): ByteWriteChannel = writeChannel()

@Deprecated(
    "Binary compatibility.",
    level = DeprecationLevel.HIDDEN
)
@JvmName("writeChannel\$default") // this suffix also adds ACC_SYNTHETIC
@Suppress("UNUSED_PARAMETER", "KDocMissingDocumentation")
fun File.writeChannel(
    pool: ObjectPool<ByteBuffer>,
    mask: Int,
    something: Any?
): ByteWriteChannel = writeChannel()

/**
 * Open a write channel for file and launch a coroutine to read from it.
 * Please note that file writing is blocking so if you are starting it on [Dispatchers.Unconfined] it may block
 * your async code
 */
@KtorExperimentalAPI
fun File.writeChannel(
    coroutineContext: CoroutineContext = Dispatchers.IO
): ByteWriteChannel = GlobalScope.reader(coroutineContext, autoFlush = true) {
    RandomAccessFile(this@writeChannel, "rw").use { file ->
        val copied = channel.copyTo(file.channel)
        file.setLength(copied) // truncate tail that could remain from the previously written data
    }
}.channel
