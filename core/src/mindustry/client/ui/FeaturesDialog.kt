package mindustry.client.ui

import arc.*
import arc.input.*
import arc.scene.ui.*
import mindustry.client.*
import mindustry.ui.dialogs.*

object FeaturesDialog : BaseDialog("@client.features") {
    override fun show(): Dialog {
        cont.clear()
        buttons.clear()
        clearListeners()

        var str = localizedAsset("features").readString("UTF-8")
        str = str.replace("\\{\\w+}".toRegex()) { res ->
            val value = res.value.removeSurrounding("{", "}")
            if (value == "p") return@replace ClientVars.clientCommandHandler.prefix // {p} becomes the client command prefix
            KeyBind.all.find { it.name == value }?.value?.key?.toString() ?: res.value // Keybind if it exists, keep as is otherwise
        }
        cont.pane(StupidMarkupParser.format(str)).growX().get()
            .setScrollingDisabled(true, false)
        addCloseButton()

        return super.show()
    }
}