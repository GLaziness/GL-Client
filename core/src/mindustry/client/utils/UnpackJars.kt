package mindustry.client.utils

import arc.*
import arc.files.*

object UnpackJars {
    fun unpack() {
        val jars = arrayOf("bcprov-jdk15on.jar", "bcpkix-jdk15on.jar", "bctls-jdk15on.jar", "bcutil-jdk15on.jar")
        val outputDir = Fi(this::class.java.protectionDomain.codeSource.location.toURI().path).parent()

        for (fi in jars) {
            println("Unloading $fi")
            val output = outputDir.child(fi)
            // GL: also replace a jar that differs from the bundled one, so a planted bcprov*.jar does not stay on the classpath
            val inp = Fi(fi, Files.FileType.classpath)
            if (!inp.exists()) return
            if (!output.exists() || output.length() != inp.length() || !output.readBytes().contentEquals(inp.readBytes())) {
                try { inp.copyTo(output) } catch (e: Throwable) { println("Could not replace $fi: $e") }
//                val inp = this::class.java.getResourceAsStream("/$fi") ?: return
//                output.writeBytes(inp.readBytes())
//                inp.close()
            }
        }
    }

    fun unpackSteamUninstaller(): Fi { // Only run as needed since it just adds more work
        val out = Fi(this::class.java.protectionDomain.codeSource.location.toURI().path).sibling("fooUninstaller.jar")
        val inp = Fi("fooUninstaller.jar", Files.FileType.classpath)
        out.delete() // Prevent the file from copying to itself
        inp.copyTo(out)
        return out
    }
}
