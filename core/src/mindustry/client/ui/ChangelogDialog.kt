package mindustry.client.ui

import arc.*
import arc.files.*
import arc.scene.ui.*
import mindustry.ui.dialogs.*

object ChangelogDialog : BaseDialog("@client.changelog") {
    private var init = false
    override fun show(): Dialog {
        if (!init) {
            init = true
            cont.pane(StupidMarkupParser.format(localizedAsset("changelog").readString("UTF-8"))).growX().scrollX(false)
            addCloseButton()
        }
        return super.show()
    }
}

/** Returns the `name_<language>` asset (e.g. `changelog_ru`) for the current game language if it exists, otherwise `name`. */
fun localizedAsset(name: String): Fi {
    val localized = Core.files.internal("${name}_${Core.bundle.locale.language}")
    return if (localized.exists()) localized else Core.files.internal(name)
}
