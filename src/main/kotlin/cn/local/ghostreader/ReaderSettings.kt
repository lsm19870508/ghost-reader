package cn.local.ghostreader

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "GhostReaderSettings", storages = [Storage("ghost-reader.xml", roamingType = RoamingType.DISABLED)])
class ReaderSettings : PersistentStateComponent<ReaderSettings.Data> {
    class Data {
        var novelPath: String = ""
        var encoding: String = "AUTO"
        var columns: Int = 72
        var pageLines: Int = 4
        var shortcuts: MutableMap<String, String> = linkedMapOf()
        var pendingStartPath: String = ""
        var pendingStartOffset: Int = -1
        var anchorLine: Int = 1
        var followCaret: Boolean = true
        var indentColumns: Int = 0
        var prefix: String = "# "
        var visible: Boolean = false
        var offsets: MutableMap<String, Int> = linkedMapOf()
    }

    private var data = Data()
    override fun getState(): Data = data
    override fun loadState(state: Data) { data = state }
}
