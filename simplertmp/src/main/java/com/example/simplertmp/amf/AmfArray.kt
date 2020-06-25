import com.example.simplertmp.Util
import com.example.simplertmp.amf.AmfData
import com.example.simplertmp.amf.AmfDecoder
import java.io.IOException
import java.io.OutputStream
import java.util.*

/**
 * AMF Array
 *
 * @author francois
 */
class AmfArray : AmfData {
    private val items: MutableList<AmfData> = ArrayList()

    override var size = 5 // Key byte + 4 length bytes
        get() {
            field += items.fold(0) { acc, data -> acc + data.size }
            return field
        }

    override fun writeTo(output: OutputStream) {
        throw UnsupportedOperationException("Not supported yet.")
    }

    @Throws(IOException::class)
    override fun readFrom(input: ByteArray) {
        val length: Int = Util.readUnsignedInt32(input)
        for (i in 0 until length) {
            val dataItem: AmfData = AmfDecoder.readFrom(input.drop(size).toByteArray())
            items.add(dataItem)
        }
    }

    /** @return the amount of items in this the array
     */
    val length: Int
        get() = items.size

    fun addItem(dataItem: AmfData) = items.add(dataItem)
}