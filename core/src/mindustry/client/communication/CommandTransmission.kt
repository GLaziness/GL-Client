package mindustry.client.communication

import arc.*
import arc.util.*
import mindustry.*
import mindustry.client.*
import mindustry.client.crypto.*
import mindustry.client.navigation.*
import mindustry.client.utils.*
import mindustry.gen.*
import mindustry.ui.dialogs.*
import java.math.*
import java.security.cert.*
import java.time.*
import kotlin.random.*

class CommandTransmission : Transmission {

    val type: Commands?
    var certSN: ByteArray
    var signature: ByteArray
    var additionalInfo: ByteArray
    var destination: Int
    var timestamp: Instant

    constructor(type: Commands?, cert: X509Certificate, destination: Player, additionalInfo: ByteArray = byteArrayOf()) {
        this.type = type
        this.certSN = cert.serialNumber.toByteArray()
        id = Random.nextLong()
        signature = ByteArray(Signatures.SIGNATURE_LENGTH)
        this.additionalInfo = additionalInfo
        this.destination = destination.id
        timestamp = Main.ntp.instant()
        sign()
    }

    override val secureOnly: Boolean = false

    companion object {
        var numStopIgnores: Int = 0
        var lastStopTime : Long = 0

        /** GL: (certificate, id) of commands already taken, so a captured frame cannot be played again. */
        private val seen = LinkedHashMap<String, Long>()

        @Synchronized
        private fun firstTime(certSN: ByteArray, id: Long): Boolean {
            val now = Time.millis()
            seen.entries.removeIf { now - it.value > Signatures.SIGNATURE_EXPIRY_SECONDS * 2000L }
            val key = BigInteger(certSN).toString(16) + ":" + id
            if (seen.containsKey(key)) return false
            if (seen.size < 1000) seen[key] = now
            return true
        }
    }
    enum class Commands(val builtinOnly: Boolean = false, val lambda: (CommandTransmission) -> Unit) {
        STOP_PATH(false, {
            val cert = Main.keyStorage.findTrusted(BigInteger(it.certSN))!!
            if (Navigation.currentlyFollowing != null) {
                val oldPath = Navigation.currentlyFollowing
                if (Main.keyStorage.builtInCerts.contains(cert)) {
                    val dialog = BaseDialog("@client.stoppath.stopped")
                    dialog.cont.add(Core.bundle.format("client.stoppath.bydev", cert.readableName))
                    dialog.buttons.button("@close", Icon.menu) { dialog.hide() }
                        .size(210f, 64f)
                    dialog.show()
                } else if (Time.timeSinceMillis(lastStopTime) > Time.toMinutes.toLong() * (1 + numStopIgnores)) {
                    numStopIgnores -= Math.min(numStopIgnores, (Time.timeSinceMillis(lastStopTime) / Time.toMinutes.toLong()).toInt() - numStopIgnores - 1)
                    Vars.ui.showCustomConfirm("@client.stoppath.stopped",
                        Core.bundle.format("client.stoppath.by", Main.keyStorage.aliasOrName(cert)),
                        Core.bundle.get("client.stoppath.continue"), Core.bundle.get("client.stoppath.stop"),
                        { Navigation.follow(oldPath); numStopIgnores ++; }, {}
                    )
                }
                lastStopTime = Time.millis()
                Navigation.stopFollowing()
            }
        }),

        /**
         * GL: used to make the client download and run a build from another repository without asking. Nothing from the
         * network may start an update any more; the entry stays so the ordinals of the other commands do not move.
         */
        UPDATE(true, {
            Log.info("Ignored a network update command from @", Main.keyStorage.findTrusted(BigInteger(it.certSN))?.readableName ?: "?")
        })
    }

    override var id: Long

    constructor(input: ByteArray, id: Long, @Suppress("UNUSED_PARAMETER") senderID: Int) {
        val buf = input.buffer()
        type = Commands.entries.getOrNull(buf.int)
        signature = buf.bytes(Signatures.SIGNATURE_LENGTH)
        certSN = buf.bytes(buf.int)
        additionalInfo = buf.bytes(buf.int)
        destination = buf.int
        timestamp = buf.long.toInstant()
        this.id = id
    }

    override fun serialize() = (type?.ordinal ?: 0).toBytes() + signature + certSN.size.toBytes() + certSN + additionalInfo.size.toBytes() + additionalInfo + destination.toBytes() + timestamp.epochSecond.toBytes()

    /** GL: the destination is signed too, so a captured command cannot be readdressed to another player. */
    private fun toSignable() = (type?.ordinal ?: 0).toBytes() + certSN + additionalInfo + id.toBytes() + timestamp.epochSecond.toBytes() + destination.toBytes()

    fun sign() {
        signature = Main.signatures.sign(toSignable()) ?: ByteArray(Signatures.SIGNATURE_LENGTH)
        certSN = Main.keyStorage.cert()?.serialNumber?.toByteArray() ?: byteArrayOf()
    }

    fun verify(): Boolean {
        if (destination != Vars.player.id) return false
        type ?: return false
        if (timestamp.ageNTP() > Signatures.SIGNATURE_EXPIRY_SECONDS) return false  // replay attacks are bad
        val res = Main.signatures.verify(toSignable(), signature, certSN)
        val valid = if (type.builtinOnly) {
            res.second ?: return false
            (res.first == Signatures.VerifyResult.VALID) && Main.keyStorage.builtInCerts.any { it.encoded.contentEquals(res.second!!.encoded) }
        } else {
            res.first == Signatures.VerifyResult.VALID
        }
        return valid && firstTime(certSN, id)
    }
}
