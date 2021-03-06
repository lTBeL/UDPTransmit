package sjj.transmit

import com.MAVLink.Parser
import com.MAVLink.common.msg_statustext
import io.reactivex.processors.PublishProcessor
import sjj.transmit.connection.ConnectInterface
import sjj.transmit.connection.ConnectState
import sjj.transmit.connection.udp.UDPConnection
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class TransmitService(private val conn: ConnectInterface = UDPConnection()) {
    val publish = PublishProcessor.create<Any>().toSerialized()
    private var status = AtomicReference<ConnectState>(ConnectState.DISCONNECT)
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var connect: Thread? = null
    fun connect() {
        println(conn)
        if (status.compareAndSet(ConnectState.DISCONNECT, ConnectState.CONNECTING)) {
            publish.onNext(ConnectState.CONNECTING)
            connect?.interrupt()
            connect = thread {
                var sendThread: Thread? = null
                try {
                    conn.openConnection()
                    status.lazySet(ConnectState.CONNECTED)
                    publish.onNext(ConnectState.CONNECTED)
                    sendThread = thread {
                        try {
                            while (status.get() != ConnectState.DISCONNECT) {
                                conn.sendBuffer(queue.take())
                            }
                        } catch (e: Exception) {
                            disconnect()
                        }
                    }
                    val readBuf = ByteArray(4096)
                    val parser = Parser()
                    while (status.get() != ConnectState.DISCONNECT) {
                        val len = conn.readDataBlock(readBuf)
                        if (len > 0) {
                            (0 until len).mapNotNull { parser.mavlink_parse_char(readBuf[it].toInt() and 0xff) }
                                    .forEach {
                                        if (it.msgid == msg_statustext.MAVLINK_MSG_ID_STATUSTEXT) {
                                            val unpack:msg_statustext = it.unpack() as msg_statustext
                                            System.err.println("${unpack.getText()}  ${unpack.warning}")
                                        }
                                    }
                            publish.onNext(Arrays.copyOf(readBuf, len))
                        } else {
                            //socket is close
                            break
                        }
                    }
                } catch (e: Exception) {

                } finally {
                    sendThread?.interrupt()
                    disconnect()
                }
            }
        }
    }

    fun disconnect() {
        println(status)
        if (status.get() == ConnectState.DISCONNECT) {
            return
        }
        status.lazySet(ConnectState.DISCONNECT)
        publish.onNext(ConnectState.DISCONNECT)
        println(status)
        connect?.interrupt()
        connect = null
    }

    fun push(byteArray: ByteArray) {
        if (status.get() != ConnectState.DISCONNECT) {
            queue.offer(byteArray)
        }
    }
}