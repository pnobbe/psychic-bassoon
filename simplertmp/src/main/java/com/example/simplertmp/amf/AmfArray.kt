import com.example.simplertmp.Util
import com.example.simplertmp.amf.AmfData
import com.example.simplertmp.amf.AmfDecoder
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

/**
 * AMF Array
 *
 * @author francois
 */
class AmfArray : AmfData {
    private var items: MutableList<AmfData>? = null

    override var size = -1
        get() {
            if (field == -1) {
                field = 5 // 1 + 4
                items?.forEach { field += it.size }
            }
            return field
        }

    override fun writeTo(output: OutputStream) {
        throw UnsupportedOperationException("Not supported yet.")
    }

    @Throws(IOException::class)
    override fun readFrom(input: ByteArray) {
        val length: Int = Util.readUnsignedInt32(input)
        size = 4

        items = ArrayList()
        for (i in 0 until length) {
            val dataItem: AmfData = AmfDecoder.readFrom(input.drop(size).toByteArray())
            size += dataItem.size
            (items as ArrayList<AmfData>).add(dataItem)
        }
    }

    /** @return the amount of items in this the array
     */
    val length: Int
        get() = items?.size ?: 0

    fun addItem(dataItem: AmfData) = items?.add(dataItem)
}