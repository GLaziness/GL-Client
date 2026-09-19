package mindustry.client

import arc.*
import arc.files.*
import arc.func.*
import arc.graphics.*
import arc.math.*
import arc.math.geom.*
import arc.struct.*
import arc.util.*
import arc.util.CommandHandler.*
import arc.util.serialization.*
import mindustry.Vars.*
import mindustry.ai.types.*
import mindustry.client.ClientVars.*
import mindustry.client.antigrief.*
import mindustry.client.communication.*
import mindustry.client.communication.Packets
import mindustry.client.navigation.*
import mindustry.client.navigation.Navigation.follow
import mindustry.client.navigation.Navigation.navigator
import mindustry.client.ui.*
import mindustry.client.utils.*
import mindustry.content.*
import mindustry.core.*
import mindustry.entities.*
import mindustry.game.*
import mindustry.gen.*
import mindustry.input.*
import mindustry.logic.*
import mindustry.net.*
import mindustry.ui.fragments.*
import mindustry.world.blocks.distribution.*
import mindustry.world.blocks.distribution.DirectionalUnloader.*
import mindustry.world.blocks.logic.*
import mindustry.world.blocks.power.*
import mindustry.world.blocks.power.PowerNode.*
import mindustry.world.blocks.sandbox.*
import mindustry.world.blocks.storage.*
import mindustry.world.blocks.storage.Unloader.*
import java.io.*
import java.math.*
import java.security.cert.*
import java.time.*
import java.time.temporal.*
import java.util.concurrent.*
import java.util.regex.*
import kotlin.math.*
import kotlin.random.*


fun setupCommands() {
    register("help [page/command]", Core.bundle.get("client.command.help.description")) { args, player ->
        if (args.isNotEmpty() && !Strings.canParseInt(args[0])) {
            val command = clientCommandHandler.commandList.find { it.text == args[0] }
            if (command != null) {
                player.sendMessage("[orange] ${clientCommandHandler.prefix}${command.text}[white] ${command.paramText}[lightgray] - ${command.description}")
                return@register
            }
            player.sendMessage(arc.Core.bundle.get("gl.ui.commands.1"))
            return@register
        }
        val commandsPerPage = 6
        var page = if (args.isNotEmpty()) Strings.parseInt(args[0]) else 1
        val pages = Mathf.ceil(clientCommandHandler.commandList.size.toFloat() / commandsPerPage)
        page--
        if (page >= pages || page < 0) {
            player.sendMessage(arc.Core.bundle.format("gl.ui.commands.p1", pages.toString()))
            return@register
        }
        val result = buildString {
            append(Strings.format("[orange]-- Client Commands Page[lightgray] @[gray]/[lightgray]@[orange] --\n\n", page + 1, pages))
            for (i in commandsPerPage * page until (commandsPerPage * (page + 1)).coerceAtMost(clientCommandHandler.commandList.size)) {
                val command = clientCommandHandler.commandList[i]
                append("[orange] !").append(command.text).append("[white] ").append(command.paramText).append("[lightgray] - ").append(command.description).append("\n")
            }
        }
        player.sendMessage(result)
    }

    register("unit-old <unit-type>", Core.bundle.get("client.command.unit.description")) { args, _ ->
        ui.unitPicker.pickUnit(content.units().min { b -> biasedLevenshtein(args[0], b.name) })
    }

    register("unit <unit-type>", Core.bundle.get("client.command.unit.description")) { args, _ ->
        ui.unitPicker.pickUnit(findUnit(args[0]))
    }

    register("uc <unit-type>", Core.bundle.get("client.command.unitcursor.description")) { args, _ ->
        ui.unitPicker.pickUnit(findUnit(args[0]), Core.input.mouseWorldX(), Core.input.mouseWorldY(), true)
    }

    register("count <unit-type>", Core.bundle.get("client.command.count.description")) { args, player ->
        val type = findUnit(args[0])
        val cap = Units.getStringCap(player.team()); var total = 0; var free = 0; var flagged = 0; var unflagged = 0; var players = 0; var command = 0; var logic = 0; var freeFlagged = 0; var logicFlagged = 0

        (player.team().data().unitCache(type) ?: Seq.with()).withEach {
            total++
            val ctrl = sense(LAccess.controlled).toInt()
            if (flag == 0.0) unflagged++
            else {
                flagged++
                if (ctrl == 0) freeFlagged++
            }
            when (ctrl) {
                GlobalVars.ctrlPlayer -> players++
                GlobalVars.ctrlCommand -> command++
                GlobalVars.ctrlProcessor -> {
                    if (flag != 0.0) logicFlagged++
                    logic++
                }
                else -> free++
            }
        }

        player.sendMessage("client.command.count.success".bundle(type.localizedName, total, cap, free, freeFlagged, flagged, unflagged, players, command, logic, logicFlagged))
    }

    // FINISHME: Add unit control/select command(s)

    register("spawn <type> [team|me] [count] [x|c] [y|c]", Core.bundle.get("client.command.spawn.description")) { args, player ->
        val type = findUnit(args[0])
        val team = if (args.size < 2) player.team() else if (args[1].lowercase() == "me") player.team() else findTeam(args[1])
        val count = if (args.size < 3 || !Strings.canParsePositiveInt(args[2])) 1 else args[2].toInt() // FINISHME: When exactly two numbers given after team, treat them as x and y instead of count and x
        val x = if (args.size >= 4 && args[3].lowercase() == "c") Core.input.mouseWorldX() else if (args.size >= 4 && Strings.canParsePositiveFloat(args[3])) args[3].toFloat() * tilesizeF else player.x
        val y = if (args.size >= 5 && args[4].lowercase() == "c") Core.input.mouseWorldY() else if (args.size >= 5 && Strings.canParsePositiveFloat(args[4])) args[4].toFloat() * tilesizeF else player.y

        if (net.client()) Call.sendChatMessage("/js for(let i = 0; i < $count; i++) UnitTypes.$type.spawn(Team.all[${team.id}], $x, $y)")
        else repeat(count) {
            type.spawn(team, x, y)
        }
    }

    register("go [x] [y]", Core.bundle.get("client.command.go.description")) { args, player ->
        try {
            if (args.size == 2) lastSentPos.set(args[0].toFloat(), args[1].toFloat())
            else throw IOException()
            Navigation.navigateTo(lastSentPos.cpy().scl(tilesizeF))
        } catch (e: Exception) {
            player.sendMessage(Core.bundle.format("client.command.coordsinvalid", clientCommandHandler.prefix + "go"))
        }
    }

    register("lookat [x] [y]", Core.bundle.get("client.command.lookat.description")) { args, player ->
        try {
            (control.input as? DesktopInput)?.panning = true
            if (args.size == 2) lastSentPos.set(args[0].toFloat(), args[1].toFloat())
            Spectate.spectate(lastSentPos.cpy().scl(tilesizeF))
        } catch (e: Exception) {
            player.sendMessage(Core.bundle.format("client.command.coordsinvalid", clientCommandHandler.prefix + "lookat"))
        }
    }

    register("tp [x] [y]", Core.bundle.get("client.command.tp.description")) { args, player ->
        try {
            if (args.size == 2) lastSentPos.set(args[0].toFloat(), args[1].toFloat())
            NetClient.setPosition(
                lastSentPos.cpy().scl(tilesizeF).x, lastSentPos.cpy().scl(tilesizeF).y)
        } catch (e: Exception) {
            player.sendMessage(Core.bundle.format("client.command.coordsinvalid", clientCommandHandler.prefix + "tp"))
        }
    }

    register("here [message...]", Core.bundle.get("client.command.here.description")) { args, player ->
        sendMessage(Strings.format("@(@, @)", if (args.isEmpty()) "" else args[0] + " ", player.tileX(), player.tileY()))
    }

    register("cursor [message...]", Core.bundle.get("client.command.cursor.description")) { args, _ ->
        sendMessage(Strings.format("@(@, @)", if (args.isEmpty()) "" else args[0] + " ", control.input.rawTileX(), control.input.rawTileY()))
    }

    register("builder [options...]", Core.bundle.get("client.command.builder.description")) { args, _: Player ->
        follow(BuildPath(if (args.isEmpty()) "" else args[0]))
    } // FINISHME: This is so scuffed lol

    register("miner [options...]", Core.bundle.get("client.command.miner.description")) { arguments, _: Player ->
        follow(MinePath(args = if (arguments.isEmpty()) "" else arguments[0]))
    } // FINISHME: This is so scuffed lol

    register("buildmine", Core.bundle.get("client.command.buildmine.description")) {_, _ -> // FINISHME: Hrnghrng properly word the bundle
        follow(BuildMinePath())
    }

    register(" [message...]", Core.bundle.get("client.command.!.description").replace("!", clientCommandHandler.prefix)) { args, _ ->
        sendMessage(clientCommandHandler.prefix + if (args.size == 1) args[0] else "")
    }

    register("shrug [message...]", Core.bundle.get("client.command.shrug.description")) { args, _ ->
        sendMessage("¯\\_(ツ)_/¯ " + if (args.size == 1) args[0] else "")
    }

    register("marker <name> [x] [y]", Core.bundle.get("client.command.marker.description")) { args, player ->
        val x = if (args.size == 3) args[1].toIntOrNull() ?: player.tileX() else player.tileX()
        val y = if (args.size == 3) args[2].toIntOrNull() ?: player.tileY() else player.tileY()
        val color = Color.HSVtoRGB(Random.nextFloat() * 360, 75f, 75f)
        Markers.add(Markers.Marker(x, y, args[0], color))
        player.sendMessage(Core.bundle.format("client.command.marker.added", x, y))
    }

    register("js <code...>", Core.bundle.get("client.command.js.description")) { args, player: Player ->
        val out = mods.scripts.runConsole(ui.consolefrag.injectConsoleVariables() + args[0])
        player.sendMessage("[accent]$out")
        Log.debug(out)
    }

    var installingKt = false
    // This command doesn't work unless the supporting jar file is on the class path
    register("kt <code...>", Core.bundle.get("client.command.kt.description")) { args, player: Player ->
        val version = 1 // The kotlin version needs bumping every so often to support new java versions. Easiest way is to redownload the file.
        val dir = Fi(ScriptEngineHolder::class.java.protectionDomain.codeSource.location.toURI().path)
        val file = dir.sibling("fooKotlinScriptSupport.jar")
        val versionFile = dir.sibling("fooKotlinScriptSupport.version") // We can't just store a value in settings because some people sync settings across computers which can cause a crash if the kotlin script support is out of date. This kind of sucks but whatever

        try { ScriptEngineHolder } catch (_: Throwable) { player.sendMessage("client.command.kt.unsupported".bundle()); return@register }

        if (installingKt) player.sendMessage("client.command.kt.installing".bundle())
        else if (!versionFile.exists() || versionFile.readString().toIntOrNull() != version || ScriptEngineHolder.kts == null) ui.showConfirm("client.command.kt.install".bundle()) {
            Log.debug("Downloading kotlin scripting support")
            installingKt = true
            becontrol.downloadJar(
                "https://github.com/mindustry-antigrief/kotlinScriptSupport/releases/latest/download/fooKotlinScriptSupport.jar",
                file,
                { versionFile.writeString(version.toString()); ui.showConfirm("client.command.kt.finished".bundle()) { restartGame() } },
                { player.sendMessage("client.command.kt.error".bundle(Strings.neatError(it))) }
            )
        }
        else player.sendMessage("[accent]${try { ScriptEngineHolder.kts!!.eval(args[0]) } catch (e: Throwable /* ScriptException */) { e.message }}")
    }

    register("/js <code...>", Core.bundle.get("client.command.serverjs.description")) { args, player ->
        player.sendMessage("[accent]${mods.scripts.runConsole(args[0])}")
        sendMessage("/js ${args[0]}")
    }

    register("scanprocs [showslightlysus]", Core.bundle.get("client.command.scanprocs.description")) { args, player ->
        val showslightlysus = args.size == 1
        player.sendMessage(arc.Core.bundle.get("gl.ui.commands.2"))
        //Getting the list of processors must be done on the main thread
        val procs = player.team().data().buildings.filterIsInstance<LogicBlock.LogicBuild>()
        //The scanning is expensive so we run it on the client thread
        clientThread.post {
            val results:MutableMap<LogicBlock.LogicBuild, LogicDetectionLevel> = mutableMapOf()
            for(proc in procs){
                results[proc] = isMalicious(proc)
            }
            Core.app.post {
                var noDetections = true
                for((block, result) in results){
                    if(result != LogicDetectionLevel.Safe && (showslightlysus || result != LogicDetectionLevel.SlightlySus)){
                        val color = when(result){
                            LogicDetectionLevel.Safe -> "white"
                            LogicDetectionLevel.SlightlySus -> "white"
                            LogicDetectionLevel.Sus -> "yellow"
                            LogicDetectionLevel.Malicious -> "scarlet"
                        }
                        ui.chatfrag.addMsg("[$color]Processor at (${block.tileX()}, ${block.tileY()}): $result").findCoords()
                        noDetections = false
                    }
                }
                if(noDetections) player.sendMessage(arc.Core.bundle.get("gl.ui.commands.3"))
                else player.sendMessage(arc.Core.bundle.get("gl.ui.commands.4"))
            }
        }
    }

    register("networking", Core.bundle.get("client.command.networking.description")) { _, player ->
        ui.chatfrag.addMsg(
            if (pluginVersion != -1F) (Core.bundle.get("client.networking.plugin") as String) else
                BlockCommunicationSystem.findProcessor()?.run { Core.bundle.format("client.networking.logicblock", tileX(), tileY()) } ?:
                BlockCommunicationSystem.findMessage()?.run { Core.bundle.format("client.networking.messageblock", tileX(), tileY()) } ?:
                Core.bundle.get("client.networking.buildplan")
        ).findCoords()
    }

    // GL: connects the grids with as few links as possible, node to node first: a laser into a building of a schematic
    // is made only when no node can be reached, so demolishing a schematic does not split the grids. A cleanup run
    // also removes node-to-building lasers that are not needed (a node sending a laser to every building of a
    // schematic, or into a schematic that a node link already connects). Node-to-node links always stay.
    register("fixpower [c] [quiet]", Core.bundle.get("client.command.fixpower.description")) { args, player ->
        val start = Time.nanos()
        val team = player.team()
        val confirmed = args.any() && args[0] == "c" // Don't configure by default
        // "!fixpower c q" is the automatic panel mode, which only adds links; every 5 minutes it runs "!fixpower c qc",
        // which also removes the extra ones
        val quiet = args.size > 1 && args[1].startsWith("q")
        val cleanup = !quiet || args[1] == "qc"
        val inProgress = !configs.isEmpty()
        val diodes = ArrayList<Pair<Building, Building>>() // back and front of every diode, checked per building
        team.data().buildings.each { d ->
            if (d is PowerDiode.PowerDiodeBuild && d.tile != null) {
                val back = d.back()
                val front = d.front()
                if (back?.power != null && front?.power != null && back.team == team && front.team == team) diodes.add(back to front)
            }
        }
        val teamGraphs = Groups.powerGraph.array.map { it.graph() }.filter { it.all.any() && it.all.first().team == team }

        // Buildings joined by the links that stay
        val index = IntIntMap()
        val parent = IntSeq()
        fun id(b: Building): Int {
            val i = index.get(b.pos(), -1)
            if (i != -1) return i
            parent.add(parent.size)
            index.put(b.pos(), parent.size - 1)
            return parent.size - 1
        }
        fun find(i: Int): Int {
            var x = i
            while (parent.get(x) != x) {
                parent.set(x, parent.get(parent.get(x)))
                x = parent.get(x)
            }
            return x
        }
        fun joined(a: Building, b: Building) = find(id(a)) == find(id(b))
        fun union(a: Building, b: Building) {
            val x = find(id(a))
            val y = find(id(b))
            if (x != y) parent.set(x, y)
        }

        // 1. Everything joins the buildings as it is, except (on a cleanup run) the node-to-building lasers
        val lasers = ArrayList<Pair<PowerNodeBuild, Building>>()
        val nodeBuilds = ArrayList<PowerNodeBuild>()
        val conns = Seq<Building>()
        for (graph in teamGraphs) for (b in graph.all) {
            if (b is PowerNodeBuild) nodeBuilds.add(b)
            for (o in b.getPowerConnections(conns)) {
                val laser = b.power.links.contains(o.pos()) || o.power.links.contains(b.pos())
                if (cleanup && laser && (b is PowerNodeBuild) != (o is PowerNodeBuild)) {
                    if (b is PowerNodeBuild) lasers.add(b to o) // each laser once, from the node side
                } else union(b, o)
            }
        }

        // No link may join the two sides of a diode (like a node linked to the battery behind a diode)
        fun diodeBetween(a: Building, b: Building): Boolean {
            val x = find(id(a))
            val y = find(id(b))
            return diodes.any {
                val p = find(id(it.first))
                val q = find(id(it.second))
                (p == x && q == y) || (p == y && q == x)
            }
        }
        val linkCount = IntIntMap() // links of a node after the changes; removed links are not subtracted, so a new link never waits for a removal
        fun links(b: Building) = linkCount.get(b.pos(), b.power.links.size)
        val added = ArrayList<Pair<PowerNodeBuild, Building>>()
        fun tryAdd(node: PowerNodeBuild, other: Building, checkInsulated: Boolean) {
            if (joined(node, other)) return
            if (links(node) >= (node.block as PowerNode).maxNodes) return
            if (other is PowerNodeBuild && links(other) >= (other.block as PowerNode).maxNodes) return
            if (diodeBetween(node, other)) return
            if (checkInsulated && PowerNode.insulated(node, other)) return
            union(node, other)
            linkCount.put(node.pos(), links(node) + 1)
            if (other is PowerNodeBuild) linkCount.put(other.pos(), links(other) + 1)
            added.add(node to other)
        }

        // 2. Node to node, shortest first (also between nodes of one grid that is held together only by lasers into a schematic)
        val nodeCandidates = ArrayList<Pair<PowerNodeBuild, PowerNodeBuild>>()
        val tree = team.data().buildingTree
        for (node in nodeBuilds) {
            val block = node.block as PowerNode
            val range = block.laserRange * tilesize
            tree?.intersect(node.x - range, node.y - range, range * 2, range * 2, Cons<Building> { o ->
                if (o is PowerNodeBuild && o.pos() > node.pos() && o.team == team && (cleanup || o.power.graph != node.power.graph) &&
                    !node.power.links.contains(o.pos()) && !o.power.links.contains(node.pos()) && block.linkValid(node, o, false)) nodeCandidates.add(node to o)
            })
        }
        nodeCandidates.sortBy { it.first.dst2(it.second) }
        for ((node, other) in nodeCandidates) tryAdd(node, other, true)

        // 3. Lasers into buildings: the existing ones stay only where they are still needed, new ones only where no node reaches.
        // A laser that would now join the two sides of a diode goes too (the node got a link past the diode instead)
        val removed = ArrayList<Pair<PowerNodeBuild, Building>>()
        val bypass = HashSet<Pair<PowerNodeBuild, Building>>()
        lasers.sortBy { it.first.dst2(it.second) }
        for (pair in lasers) {
            if (joined(pair.first, pair.second)) removed.add(pair)
            else if (diodeBetween(pair.first, pair.second)) {
                removed.add(pair)
                bypass.add(pair)
            } else union(pair.first, pair.second)
        }
        val candidates = ArrayList<Pair<PowerNodeBuild, Building>>()
        for (node in nodeBuilds) (node.block as PowerNode).getPotentialLinks(node.tile, team) { candidates.add(node to it) }
        candidates.sortBy { it.first.dst2(it.second) }
        for ((node, other) in candidates) tryAdd(node, other, false) // getPotentialLinks already skips insulated ones

        val n = added.size
        val r = removed.size
        val nodes = (added.map { it.first.pos() } + removed.map { it.first.pos() }).toSet().size
        if (confirmed && n == 0 && r == 0) {
            if (!quiet) ui.chatfrag.addMsg(Core.bundle.get("client.command.fixpower.nothing")).format()
            return@register
        }

        val msg = ui.chatfrag.addMsg("")

        msg.message = when {
            confirmed && inProgress -> Core.bundle.format("client.command.fixpower.inprogress", configs.size, n, nodes)
            confirmed -> { // Actually fix the connections: new links first, so the power is never cut
                for ((a, b) in added) configs.add(PowerLinkRequest(a.pos(), b.pos(), true))
                for (pair in removed) configs.add(PowerLinkRequest(pair.first.pos(), pair.second.pos(), false, pair in bypass))
                configs.add { // This runs after the connections are made
                    val active = Groups.powerGraph.array.select { it.graph().all.first().team == team && it.graph().all.contains { it !is ItemBridge.ItemBridgeBuild || it.shouldConsume() } }.size // We don't care about unlinked bridge ends
                    msg.message = Core.bundle.format("client.command.fixpower.success", n, active, nodes, r)
                    msg.format()
                }
                Core.bundle.format("client.command.fixpower.confirmed", n, nodes, r)
            }
            else -> Core.bundle.format("client.command.fixpower.confirm", n, teamGraphs.size, nodes, r)
        }
        msg.format()

        Log.debug("Ran fixpower in @ms", Time.millisSinceNanos(start))
    }

    register("fixcode [c/r/l]", Core.bundle.get("client.command.fixcode.description")) { args, _ ->
        ProcessorPatcher.fixCode(args.firstOrNull())
    }

    register("distance [distance]", Core.bundle.get("client.command.distance.description")) { args, player ->
        if (args.size != 1) player.sendMessage(arc.Core.bundle.format("gl.ui.commands.p2", Core.settings.getFloat("assistdistance", 5f).toString()))
        else {
            Core.settings.put("assistdistance", abs(Strings.parseFloat(args[0], 5f)))
            player.sendMessage(arc.Core.bundle.format("gl.ui.commands.p3", Core.settings.getFloat("assistdistance").toString()))
        }
    }

    register("circleassist [speed]", Core.bundle.get("client.command.circleassist.description")) { args, player ->
        val defaultSpeed = 0.25f
        if (args.size != 1) player.sendMessage(Core.bundle.format("client.command.circleassist.lookup", Core.settings.getFloat("circleassistspeed", defaultSpeed), defaultSpeed))
        else {
            val circling = args[0] != "0"
            Core.settings.put("circleassist", circling)
            val assistPath = Navigation.currentlyFollowing as? AssistPath
            if (assistPath != null) assistPath.circling = circling
            if(circling){
                Core.settings.put("circleassistspeed", args[0].toFloatOrNull() ?: defaultSpeed)
                player.sendMessage(Core.bundle.format("client.command.circleassist.success", Core.settings.getFloat("circleassistspeed"), defaultSpeed))
            }
            else player.sendMessage(Core.bundle.get("client.command.circleassist.disabled"))
        }
    }

    register("assistshape [shape]", Core.bundle.get("client.command.assistshape.description")) { args, player ->
        val options = AssistPath.Companion.OrbitShape.entries.joinToString("/") { it.name }
        if (args.isEmpty()) {
            val current = Core.settings.getString("circleassistshape", "circle")
            player.sendMessage(Core.bundle.format("client.command.assistshape.lookup", current, options))
        } else {
            val raw = args[0].lowercase().replace("-", "").replace("_", "")
            val key = when (raw) {
                "8", "eight", "fig8", "figureeight", "lemniscate" -> "figure8"
                "holdpos", "stay" -> "hold"
                "lines", "patrol" -> "line"
                else -> raw
            }
            val shape = AssistPath.Companion.OrbitShape.entries.find { it.name.equals(key, ignoreCase = true) }
            if (shape == null) {
                player.sendMessage(Core.bundle.format("client.command.assistshape.invalid", options))
            } else {
                Core.settings.put("circleassistshape", shape.name)
                player.sendMessage(Core.bundle.format("client.command.assistshape.success", shape.name))
            }
        }
    }

    register("clearghosts [c]", Core.bundle.get("client.command.clearghosts.description")) { args, player ->
        val confirmed = args.any() && args[0].startsWith("c") // Don't clear by default
        val all = confirmed && isDeveloper() && args[0] == "clear"
        val plans = mutableListOf<Int>()

        for (plan in player.team().data().plans) {
            if (!(all || Navigation.getTree().use { any(plan.x * tilesizeF, plan.y * tilesizeF, plan.block.size * tilesizeF, plan.block.size * tilesizeF) })) continue

            plans.add(Point2.pack(plan.x.toInt(), plan.y.toInt()))
        }

        if (confirmed) {
            plans.chunked(200) { configs.add { Call.deletePlans(player, it.toIntArray()) } }
            player.sendMessage(arc.Core.bundle.format("gl.ui.commands.p4", plans.size.toString(), (player.team().data().plans.size - plans.size).toString()))
        } else player.sendMessage(arc.Core.bundle.format("gl.ui.commands.p5", plans.size.toString(), player.team().data().plans.size.toString(), clientCommandHandler.prefix))
    }

    register("e <certname> <message...>", Core.bundle.get("client.command.e.description")) { args, _ ->
        val certname = args[0]
        val msg = args[1]

        connectTls(certname) { comms, cert ->
            comms.send(MessageTransmission(msg))
            ui.chatfrag.addMessage(
                msg,
                Main.keyStorage.cert()?.readableName ?: "you",
                encrypted,
                "[coral][[[white]${Main.keyStorage.cert()?.readableName ?: "you"} [accent]-> [white]${Main.keyStorage.aliasOrName(cert)}[coral]][white]: ",
                msg
            )
            lastCertName = cert.readableName
        }
    }

    register("stoppathing <name/id...>", Core.bundle.get("client.command.stoppathing.description")) { args, _ ->
        val name = args.joinToString(" ")
        val player = Groups.player.find { it.id == Strings.parseInt(name) } ?:
            Groups.player.minByOrNull { biasedLevenshtein(Strings.stripColors(it.name), name, false, true) }!!
        Main.send(CommandTransmission(CommandTransmission.Commands.STOP_PATH, Main.keyStorage.cert() ?: run {
            player.sendMessage(arc.Core.bundle.get("gl.ui.commands.5"))
            return@register
        }, player))
        ui.chatfrag.addMsg("Stopped pathing of player ${player.name}[white].")
    }

    register("g [message...]", Core.bundle.get("client.command.g.description")) { args, player ->
        // GL: global chat between GL Client players
        if (args.isEmpty()) player.sendMessage(mindustry.client.utils.GlobalChat.status())
        else mindustry.client.utils.GlobalChat.send(args[0]) // writes the reason to the chat when it cannot send
    }

    register("ghelp", Core.bundle.get("client.command.ghelp.description")) { _, player ->
        // GL: all commands and controls of the global chat
        player.sendMessage("[accent]" + Core.bundle.get("client.globalchat.help.title") + "[]\n" + Core.bundle.get("client.globalchat.help"))
    }

    register("gs [message...]", Core.bundle.get("client.command.gs.description")) { args, player ->
        // GL: chat of the server I am on (all its modes), through the global chat server
        val chat = mindustry.client.utils.GlobalChat.channel()
        if (args.isEmpty()) player.sendMessage(mindustry.client.utils.GlobalChat.icons(if (chat.isEmpty()) Core.bundle.get("client.globalchat.sys.noserver")
            else Core.bundle.format("client.globalchat.serverstatus", chat, mindustry.client.utils.GlobalChat.serverOnline())))
        else mindustry.client.utils.GlobalChat.send(args[0], true)
    }

    register("gm [action] [target...]", Core.bundle.get("client.command.gm.description")) { args, player ->
        // GL: global chat moderation, the chat server checks the rights
        val actions = listOf("mute", "unmute", "ban", "ban30", "banforever", "sban", "sbanforever", "unban", "addmod", "delmod", "addcur", "delcur", "list")
        if (args.isEmpty() || args[0] !in actions) player.sendMessage(mindustry.client.utils.GlobalChat.icons(Core.bundle.get("client.globalchat.gmhelp")))
        else mindustry.client.utils.GlobalChat.moderate(args[0], if (args.size > 1) args[1] else "")
    }

    register("c <message...>", Core.bundle.get("client.command.c.description")) { args, _ ->
        Main.send(ClientMessageTransmission(args[0]).apply { addToChatfrag() })
    }

    register("mapinfo [team]", Core.bundle.get("client.command.mapinfo.description")) { args, player ->
        val team = if (args.isEmpty()) player.team() else findTeam(args[0])
        player.sendMessage(with(state) {
            """
            [accent]Name: ${map.name()}[accent] (by: ${map.author()}[accent])
            Size: ${world.width()}x${world.height()}
            Team: ${team.name}
            Map Time: ${UI.formatTime(tick.toFloat())}
            Build Speed (Unit Factories): ${rules.buildSpeed(team)}x (${rules.unitBuildSpeed(team)}x)
            Build Cost (Refund): ${rules.buildCostMultiplier}x (${rules.deconstructRefundMultiplier}x)
            Block Health (Damage): ${rules.blockHealth(team)}x (${rules.blockDamage(team)}x)
            Unit Damage: ${rules.unitDamage(team)}x
            Unit Health: ${rules.unitHealth(team)}x
            Core Capture: ${rules.coreCapture}
            Core Incinerates: ${rules.coreIncinerates}
            Core Modifies Unit Cap: ${rules.unitCapVariable}
            Only Deposit Core: ${rules.onlyDepositCore}
            """.trimIndent()
        })
    }

    register("binds <type>", Core.bundle.get("client.command.binds.description")) { args, player -> // FINISHME: Pagination
        val type = findUnit(args[0])

        player.team().data().unitCache(type)
            ?.retainAll { it.controller() is LogicAI }
            ?.groupBy { (it.controller() as LogicAI).controller }
            ?.forEach { (build, units) ->
                val msg = ui.chatfrag.addMsg("x${units.size} [accent](${build.tileX()}, ${build.tileY()})")
                NetClient.findCoords(msg)
            }
    }

    register("unloaders <item> [enabledOnly] [setOnly]", Core.bundle.get("client.command.unloaders.description")) { args, player ->
        val item = findItem(args[0])
        val enabledOnly = args.size < 2 || parseBool(args[1])
        val setOnly = args.size < 3 || parseBool(args[2])

        val linkedCores = ObjectSet<Building>()
        player.team().cores().forEach { core ->  // Add all cores and their adjacent containers/vaults to the list
            linkedCores.add(core)
            core.proximity.forEach {
                if ((it.block as? StorageBlock)?.coreMerge == true) linkedCores.add(it)
            }
        }

        val coords = ObjectSet<Building>()
        for (core in linkedCores) { // Iterate through proximity of all cores & adjacent vaults looking for unloaders of the specified type
            core.proximity.forEach {
                if (it !is UnloaderBuild && !(it is DirectionalUnloaderBuild && (it.block as? DirectionalUnloader)?.allowCoreUnload == true)) return@forEach
                if (!it.enabled && enabledOnly) return@forEach
                if (it.config() != item && (setOnly || it.config() != null)) return@forEach
                if (it is DirectionalUnloaderBuild && !linkedCores.contains(it.back())) return@forEach // Erekir unloaders only unload from the tile behind
                if (!coords.add(it)) return@forEach // We have already printed this tile's coords

                ui.chatfrag.addMsg("[accent](${it.tileX()}, ${it.tileY()})").findCoords()
            }
        }
    }

    register("blank", Core.bundle.get("client.command.blank.description")) { _, _ ->
        sendMessage("\u200B")
    }

    register("replacemessage <from> <to> [useRegex=t]", Core.bundle.get("client.command.replacemessage.description")) { args, player ->
        if (args[0].length < 3) {
            player.sendMessage(arc.Core.bundle.get("gl.ui.commands.6"))
            return@register
        }
        val useRegex = args.size > 2 && args[2] == "t"
        replaceMsg(args[0], useRegex, args[0], useRegex, args[1])
    }

    register(
        "replacemsgif <matches> <from> <to> [useMatchRegex=t] [useFromRegex=t]",
        Core.bundle.get("client.command.replacemsgif.description")
    ) { args, player ->
        if (args[0].length < 3) {
            player.sendMessage(arc.Core.bundle.get("gl.ui.commands.6"))
            return@register
        }
        replaceMsg(args[0], args.size > 3 && args[3] == "t", args[1], args.size > 4 && args[4] == "t", args[2])
    }

    register("phasei <interval>", Core.bundle.get("client.command.phasei.description")) { args, player ->
        try{
            val interval = Integer.parseInt(args[0])
            val maxInterval = (Blocks.phaseConveyor as ItemBridge).range
            if(interval < 1 || interval > maxInterval){
                player.sendMessage(Core.bundle.format("client.command.phasei.invalidargs", maxInterval))
                return@register
            }
            Core.settings.put("phaseweaveinterval", interval)
            Core.settings.remove("weaveEndInterval")
            player.sendMessage(Core.bundle.format("client.command.phasei.success", interval))
        } catch (e : Exception){
            player.sendMessage(Core.bundle.format("client.command.phasei.err"))
        }
   }

    register("pathing", Core.bundle.get("client.command.pathing.description")) { _, player ->
        if (navigator is AStarNavigator) {
            navigator = AStarNavigatorOptimised
            player.sendMessage(arc.Core.bundle.get("gl.ui.commands.7"))
        } else if (navigator is AStarNavigatorOptimised) {
            navigator = AStarNavigator
            player.sendMessage(arc.Core.bundle.get("gl.ui.commands.8"))
        }
    }

    register("pic [quality]", Core.bundle.get("client.command.pic.description")) { args, player ->
        if (args.isEmpty()) {
            player.sendMessage(Core.bundle.format("client.command.pic.invalidargs", jpegQuality, if (jpegQuality == 0f) " (png)" else ""))
            return@register
        }
        try {
            val quality = args[0].toFloat()
            if (quality !in 0f .. 1f) {
                player.sendMessage(Core.bundle.format("client.command.pic.invalidargs", jpegQuality, if (jpegQuality == 0f) " (png)" else ""))
                return@register
            }
            jpegQuality = quality
            Core.settings.put("commpicquality", quality)
            player.sendMessage(Core.bundle.format("client.command.pic.success", if (quality == 0f) "png" else quality))
        } catch (e: Exception) {
            Log.err(e)
            if (e is NumberFormatException)
                player.sendMessage(Core.bundle.format("client.command.pic.invalidargs", jpegQuality, if (jpegQuality == 0f) " (png)" else ""))
            else player.sendMessage(Core.bundle.get("client.command.pic.error"))
        }
    }

    register("procfind [option] [argument...]", Core.bundle.get("client.command.procfind.description")) { args, player ->
        if(args.isEmpty()) player.sendMessage(Core.bundle.get("client.command.procfind.help")) // This one looks long and cursed on the bundle
        else when (args[0]) {
            "query" -> {
                if (args.size < 2) {
                    player.sendMessage(Core.bundle.get("client.command.procfind.query.empty"))
                    return@register
                }
                try {
                    ProcessorFinder.search((if (args[1] == "*") ".*" else args[1]).toRegex())
                } catch(e:PatternSyntaxException){
                    player.sendMessage(Core.bundle.format("client.command.procfind.query.invalid", args[1]))
                }
            }
            "queries" -> {
                val sb = StringBuilder(Core.bundle.get("client.command.procfind.queries")).append("\n")
                ProcessorFinder.queries.forEach { r -> sb.append("\n").append(r.toPattern().pattern()) }
                player.sendMessage(sb.toString())
            }
            "searchall" -> ProcessorFinder.searchAll()
            "clear" -> {
                player.sendMessage(Core.bundle.format("client.command.procfind.clear", ProcessorFinder.getCount()))
                ProcessorFinder.clear()
            }
            "list" -> ProcessorFinder.list()
        }
    }

    register("voids [count]", Core.bundle.get("client.command.voids.description")) { args, player ->
        var count = 1
        if (args.isNotEmpty()) {
            try {
                count = args[0].toInt()
            }
            catch (e: Exception) {
                player.sendMessage(Core.bundle.get("client.command.voids.invalidargs"))
            }
        }
        if (count == 0) count = -1

        clientThread.post {
            val voids = Seq<Building>()
            for (tile in world.tiles) {
                if (tile.block() is PowerVoid) voids.add(tile.build)
            }
            voids.sort { c -> player.dst(c) }

            if (voids.size > 0) {
                val sb = StringBuilder()
                if (count > 0) sb.append(Core.bundle.format("client.command.voids.list", voids.size, count))
                else sb.append(Core.bundle.format("client.command.voids.listall", voids.size))
                for (void in voids) {
                    if (count == 0) break
                    sb.append(String.format("(%d,%d) ", void.tileX(), void.tileY()))
                    if (count > 0) count--
                }
                Core.app.post { player.sendMessage(sb.toString()) }
            }
            else Core.app.post { player.sendMessage(Core.bundle.get("client.command.voids.novoids")) }
        }
    }

    register("gamejointext [text...]", Core.bundle.get("client.command.gamejointext.description")) { args, player ->
        if (args.isEmpty() || args[0] == "") player.sendMessage(Core.bundle.get("client.command.gamejointext.clear"))
        else {
            Core.settings.put("gamejointext", args[0])
            player.sendMessage(Core.bundle.format("client.command.gamejointext.success", args[0]))
        }
    }

    register("gamewintext [text...]", Core.bundle.get("client.command.gamewintext.description")) {args, player ->
        if (args.isEmpty() || args[0] == "") player.sendMessage(Core.bundle.get("client.command.gamewintext.success"))
        else {
            Core.settings.put("gamewintext", args[0])
            player.sendMessage(Core.bundle.format("client.command.gamewintext.success", args[0]))
        }
    }

    register("gamelosetext [text...]", Core.bundle.get("client.command.gamelosetext.description")) {args, player ->
        if (args.isEmpty() || args[0] == "") player.sendMessage(Core.bundle.get("client.command.gamelosetext.clear"))
        else {
            Core.settings.put("gamelosetext", args[0])
            player.sendMessage(Core.bundle.get("client.command.gamelosetext.success", args[0]))
        }
    }

    register("text <option> [name] [text...]", Core.bundle.get("client.command.text.description")) { args, player ->
        when (args[0]) {
            "edit", "e" -> {
                if (args.size <= 1) {
                    player.sendMessage(Core.bundle.get("client.command.text.edit.noselected"))
                    return@register
                }
                if (args.size <= 2) {
                    player.sendMessage(Core.bundle.format("client.command.text.edit.clear", args[1]))
                    if (Core.settings.get("text-${args[1]}", "").toString().isNotEmpty()) Core.settings.remove("text-${args[1]}")
                }
                else {
                    val text = args.drop(2).joinToString(" ")
                    Core.settings.put("text-${args[1]}", text)
                    player.sendMessage(Core.bundle.format("client.command.text.edit.success", args[1], text))
                }
            }
            "say", "s" -> {
                if (args.size <= 1) {
                    player.sendMessage(Core.bundle.get("client.command.text.say.noselected"))
                    return@register
                }
                val text = Core.settings.get("text-${args[1]}", "").toString()
                if (text.isEmpty()) player.sendMessage(Core.bundle.format("client.command.text.notext", args[1]))
                else Call.sendChatMessage(text)
            }
            "run", "r" -> {
                if (args.size <= 1) {
                    player.sendMessage(Core.bundle.get("client.command.text.run.noselected"))
                    return@register
                }
                val text = Core.settings.get("text-${args[1]}", "").toString()
                if (text.isEmpty()) player.sendMessage(Core.bundle.format("client.command.text.notext", args[1]))
                else ChatFragment.handleClientCommand(text)
            }
            "list", "l" -> {
                var exists = false
                val texts = Seq<String>()
                for (setting in Core.settings.keys()) {
                    if (setting.startsWith("text-")) {
                        exists = true
                        texts.add(setting)
                    }
                }
                if (!exists) player.sendMessage(Core.bundle.get("client.command.text.notexts"))
                else {
                    val sb = StringBuilder(Core.bundle.get("client.command.text.list"))
                    texts.forEach { sb.append("\n${it.drop(5)} [gray]-[] ${Core.settings.getString(it)}") }
                    player.sendMessage(sb.toString())
                }
            }
            else -> player.sendMessage(Core.bundle.get("client.command.text.invalidargs"))
        }
    }

    register("bannedcontent", Core.bundle.get("client.command.bannedcontent.description")) { _, player ->
        val sb = StringBuilder(Core.bundle.get("client.command.bannedcontent.text")).append(" ")
        state.rules.bannedUnits.forEach { sb.append(it.localizedName).append(" ") }
        state.rules.bannedBlocks.forEach { sb.append(it.localizedName).append(" ") }
        player.sendMessage(sb.toString())
    }

    register("mute <player>", Core.bundle.get("client.command.mute.description")) { args, player ->
        val id = args[0].toIntOrNull()
        val target = if (id != null && Groups.player.getByID(id) != null) Groups.player.getByID(id)
        else Groups.player.minBy { p -> biasedLevenshtein(p.name, args[0]) }

        player.sendMessage(Core.bundle.format("client.command.mute.success", target.coloredName(), target.id))
        val previous = mutedPlayers.firstOrNull { pair -> pair.first.name == target.name || pair.second == target.id }
        if (previous != null) mutedPlayers.remove(previous)
        mutedPlayers.add(Pair(target, target.id))
    }

    register("unmute <player>", Core.bundle.get("client.command.unmute.description")) { args, player ->
        val id = args[0].toIntOrNull()
        val target = Groups.player.minBy { p -> biasedLevenshtein(p.name, args[0]) }
        val match = mutedPlayers.firstOrNull { p -> (id != null && p.second == id) || (p.first != null && p.first.name == target.name) }
        if (match != null) {
            if (target != null) player.sendMessage(Core.bundle.format("client.command.unmute.success", target.coloredName(), target.id))
            else player.sendMessage(Core.bundle.format("client.command.unmute.byid", match.second))
            mutedPlayers.remove(match)
        }
        else player.sendMessage(Core.bundle.get("client.command.mute.notmuted"))
    }

    register("clearmutes", Core.bundle.get("client.command.clearmutes.description")) { _, player ->
        player.sendMessage(Core.bundle.get("client.command.clearmutes.success"))
        mutedPlayers.clear()
    }

    // FINISHME: Server specific commands would be nice.
    register("ohno", "client.command.autoohno.description".bundle()) { _, player ->
        if (!Fish()) return@register
        player.sendMessage("client.command.autoohno.running".bundle())
        Fish.ohno(true)
    }

    // Special commands

    register("seer", "client.command.seer.description".bundle()) { _, player ->
        if (!Core.settings.getBool("seer-enabled")) {
            player.sendMessage(Core.bundle.get("client.command.seer.disabled"))
            return@register
        }
        SeerDialog.show()
    }

    register("admin [option]", Core.bundle.get("client.command.admin.description")) { args, player ->
        val arg = if (args.isEmpty()) "" else args[0]
        when (arg.lowercase()) {
            "s", "settings" -> {
                ui.settings.show()
                ui.settings.visible(4)
            }
            "l", "leaves" -> Client.leaves?.leftList() ?: player.sendMessage(arc.Core.bundle.get("gl.ui.commands.9"))
            else -> player.sendMessage(arc.Core.bundle.get("gl.ui.commands.10"))
        }
    }


    // Symbol replacements

    registerReplace('%', "c", "cursor") {
        "(${control.input.rawTileX()}, ${control.input.rawTileY()})"
    }

    registerReplace('%', "s", "shrug") {
        "¯\\_(ツ)_/¯"
    }

    registerReplace('%', "h", "here") {
        "(${player.tileX()}, ${player.tileY()})"
    }

    //FINISHME: add various % for gamerules

    // Experimentals (and funny commands)
    if (Core.settings.getBool("client-experimentals") || OS.hasProp("policone")) {
        register("poli", "Spelling is hard. This will make sure you never forget how to spell the plural of poly, you're welcome.") { _, _ ->
            sendMessage("Unlike a roly-poly whose plural is roly-polies, the plural form of poly is polys. Please remember this, thanks! :)")
        }

        register("silicone", "Spelling is hard. This will make sure you never forget how to spell silicon, you're welcome.") { _, _ ->
            sendMessage("Silicon is a naturally occurring chemical element, whereas silicone is a synthetic substance. They are not the same, please get it right!")
        }

        register("hh [h]", "!") { args, player ->
            if (!net.client()) return@register
            val u = if (args.any()) findUnit(args[0]) else player.unit().type
            val current = ui.join.lastHost ?: return@register
            if (current.group == null) current.group = ui.join.communityHosts.find { it == current } ?.group ?: return@register
            switchTo = ui.join.communityHosts.filterTo(arrayListOf<Any>()) { it.group == current.group && it != current && (it.version == Version.build || Version.build == -1) }.apply { add(current); add(u) }
            val first = switchTo!!.removeAt(0) as Host
            NetClient.connect(first.address, first.port)
        }

        register("rollback <time> <buildrange>", """
            Retrieves blocks from tile logs and places them into buildplan.
                [white]time[] How long ago to rollback to, in minutes before now
                [white]buildrange[] Radius within which buildings are rebuilt
        """.trimIndent()) { args, player ->
            var time: Instant
            val range: Float
            try {
                time = Instant.now().minus(args[0].toLong(), ChronoUnit.MINUTES)
                range = args[1].toFloat() * tilesize
            } catch (_: Exception) {
                player.sendMessage(arc.Core.bundle.get("gl.ui.commands.11"))
                return@register
            }
            Tmp.r1.set(player.x - range, player.y - range, range * 2, range * 2)
            rollbackTiles(world.tiles.filter { it.getBounds(Tmp.r2).overlaps(Tmp.r1) && it.within(player.x, player.y, range) }, time)
        }

        register("rebuild <start> <end> <buildrange>", """
            Rebuilds the last building for each tile over a time range, by using tile logs and placing them into buildplan.
                [white]start[] Start of time interval to rebuild, in minutes before now
                [white]end[] End of time interval to rebuild, in minutes before now
                [white]buildrange[] Radius within which buildings are rebuilt
        """.trimIndent()) { args, player ->
            val timeStart: Instant
            val timeEnd: Instant
            val range: Float
            try {
                timeStart = Instant.now().minus(args[0].toLong(), ChronoUnit.MINUTES)
                timeEnd = Instant.now().minus(args[1].toLong(), ChronoUnit.MINUTES)
                range = args[2].toFloat() * tilesize
            }
            catch (_: Exception) {
                player.sendMessage(arc.Core.bundle.get("gl.ui.commands.12"))
                return@register
            }
            if (timeStart >= timeEnd) { // I hate dealing with people
                player.sendMessage(arc.Core.bundle.get("gl.ui.commands.13"))
                return@register
            }

            // FINISHME: Add some filter for team. And optionally omit the collision code
            Tmp.r1.set(player.x - range, player.y - range, range * 2, range * 2)
            rebuildBroken(world.tiles.filter { it.getBounds(Tmp.r2).overlaps(Tmp.r1) && it.within(player.x, player.y, range) && it.block() === Blocks.air },
                timeStart, timeEnd, range)
        }

        register("undo <player> [range]", "Undo Configs from a specific player (get rekt griefers)") { args, player ->
            val range: Float
            val id: Int
            try { // FINISHME: Strings.parseInt/Float
                id = args[0].toInt()
                range = if (args.size >= 2) args[1].toFloat() * tilesize else Float.MAX_VALUE
            }
            catch (_: Exception) {
                player.sendMessage(arc.Core.bundle.get("gl.ui.commands.14"))
                return@register
            }

            Tmp.r1.set(player.x - range, player.y - range, range * 2, range * 2)
            undoPlayer(world.tiles.filter { it.getBounds(Tmp.r2).overlaps(Tmp.r1) && it.within(player.x, player.y, range) }, id)
        }

        register("upload", "This is a terrible idea") { _, player -> // FINISHME: This is a super lazy implementation
            val results = ConcurrentLinkedQueue<String>() // Results of the http requests
            val pool = Threads.unboundedExecutor("Schematic Upload", 1)
            val sb = StringBuilder()

            fun uploadSchematics() { // FINISHME: We really need to handle failed uploads
                val str = sb.substring(0, sb.length - 1) // Drop the trailing \n
                Log.debug("Uploading schematic list of length ${str.length}")
                pool.execute { Http.post("https://cancer-co.de/upload", "text=" + Strings.encode(str)).timeout(60_000).block { results.add(it.resultAsString) } }
                sb.clear()
            }

            Threads.daemon { // Writing base64 is slow (plus we block to wait for requests)
                schematics.all().each {
                    val b = schematics.writeBase64(it)
                    if (b.length + 1 > 8_000_000) { // Who in their right mind has a schematic that's over 8 million characters
                        Core.app.post { player.sendMessage(arc.Core.bundle.format("gl.ui.commands.p6", it.name())) }
                        return@each
                    }
                    if (sb.length + b.length > 8_000_000) uploadSchematics()

                    sb.append(b).append('\n')
                }

                if (sb.isNotEmpty()) uploadSchematics() // Upload any leftovers
                Threads.await(pool) // Wait for all requests to finish before continuing

                sb.append("[accent]Your schematics have been uploaded: ")
                results.forEach {
                    val json = Jval.read(it)
                    sb.append(json.getString("url").substringAfterLast('/')).append(' ')
                }
                sb.setLength(sb.length - 1) // Remove extra appended space
                val ids = sb.toString().substringAfter(": ")
                Core.app.post { ui.chatfrag.addMsg(sb.toString()).addButton(0, sb.length) { Core.app.clipboardText = ids } }
            }
        }

        register("view <name> <ids...>", "This is an equally terrible idea") { args, player -> // FINISHME: Why did I think this was a good idea?
            val browser = ui.schematicBrowser
            val dest = browser.loadedRepositories.get(args[0]) { Seq() }.clear()
            val split = args[1].split(' ')

            split.forEach { id ->
                Http.get("https://cancer-co.de/raw/$id").timeout(60_000).submit { r ->  // FINISHME: Add handling for failed http requests
                    val str = r.resultAsString
                    if (str == "Paste not found!") { // FINISHME: Improve messaging for failed loads
                        player.sendMessage(arc.Core.bundle.format("gl.ui.commands.p7", id.toString()))
                        return@submit
                    }
                    val out = Seq<Schematic>()
                    for (s in str.split('\n')) out.add(Schematics.readBase64(s))

                    Core.app.post { // Do this on the main thread
                        player.sendMessage(arc.Core.bundle.format("gl.ui.commands.p8", id.toString()))
                        dest.add(out)
                        browser.rebuildResults()
                    }
                }
            }
        }
    }
}

/** Registers a command.
 *
 * @param format The format of the command, basically name and parameters together. Example:
 *      "help [page]"
 * @param description The description of the command.
 * @param runner A lambda to run when the command is invoked.
 */
fun register(format: String, description: String = "", runner: CommandRunner<Player>) {
    val args = if (format.contains(' ')) format.substringAfter(' ') else ""
    clientCommandHandler.register(format.substringBefore(' '), args, description, runner)
}

fun replaceMsg(match: String, matchRegex: Boolean, from: String, fromRegex: Boolean, to: String){
    clientThread.post {
        var matchReg = Regex("No. Something went wrong.")
        var fromReg = Regex("No. Something went wrong.")
        if(matchRegex) matchReg = match.toRegex()
        if(fromRegex) fromReg = from.toRegex()
        var num = 0
        val seq = player.team().data().buildings.filterIsInstance<MessageBlock.MessageBuild>()
        seq.forEach {
            val msg = it.message.toString()
            if((!matchRegex && !msg.contains(match)) || (matchRegex && !matchReg.matches(msg))) return@forEach
            val msg2 = if(fromRegex) msg.replace(fromReg, to)
            else msg.replace(from, to)
            configs.add(ConfigRequest(it.tileX(), it.tileY(), msg2))
            num++
        }
        player.sendMessage(arc.Core.bundle.format("gl.ui.commands.p9", num.toString()))
    }
}

fun registerReplace(symbol: Char = '%', vararg cmds: String, runner: Prov<String>) = cmds.forEach { cmd ->
    val seq = containsCommandHandler.get(symbol) { Seq() }
    seq.add(Pair(cmd, runner))
    seq.sort(Structs.comparingInt { -it.first.length })
}

private fun connectTls(certname: String, onFinish: (Packets.CommunicationClient, X509Certificate) -> Unit) {
    val cert = Main.keyStorage.aliases().firstOrNull { it.second.equals(certname, true) }
        ?.run { Main.keyStorage.findTrusted(BigInteger(first)) }
        ?: Main.keyStorage.trusted().firstOrNull { it.readableName.equals(certname, true) }

    cert ?: run {
        player.sendMessage(Core.bundle.format("client.tls.foundnocert", certname))
        return
    }

    if (cert == Main.keyStorage.cert()) {
        player.sendMessage(Core.bundle.get("client.tls.connectself"))
        return
    }

    val preexistingConnection = Main.tlsPeers.firstOrNull { it.second.peer.expectedCert.encoded.contentEquals(cert.encoded) }

    if (preexistingConnection != null) {
        if (preexistingConnection.second.peer.handshakeDone) {
            onFinish(preexistingConnection.first, cert)
        } else {
            player.sendMessage(Core.bundle.get("client.tls.incompletehandshake"))
        }
    } else {
        player.sendMessage(Core.bundle.get("client.tls.sendingrequest"))
        Main.connectTls(cert, {
            player.sendMessage(Core.bundle.get("client.tls.connected"))
            // delayed to make sure receiving end is ready
            Timer.schedule({ onFinish(it, cert) }, .1F)
        }, { player.sendMessage(Core.bundle.get("client.tls.setupcommunication")) })
    }
}

/** GL: links or unlinks a power node, checked right before sending, so a link someone already changed is not toggled back.
 *  A laser is cut only while both ends stay connected without it (the new links may still be on their way),
 *  or, when [diode] is set, while it joins the two sides of a diode. */
private class PowerLinkRequest(val node: Int, val target: Int, val link: Boolean, val diode: Boolean = false) : Runnable {
    private var deadline = 0L
    private var retryAt = 0L

    override fun run() {
        val build = world.build(node) as? PowerNodeBuild ?: return
        if (build.team != player.team() || build.power.links.contains(target) == link) return
        val other = world.build(target) ?: return
        if (other.power == null) return
        if (!link) {
            val now = Time.millis()
            if (deadline == 0L) deadline = now + 10_000
            if (now < retryAt) {
                configs.add(this)
                return
            }
            if (if (diode) !shortsDiode(build) else !connectedWithout(build, other)) {
                if (now < deadline) {
                    retryAt = now + 500
                    configs.add(this)
                }
                return
            }
        }
        Call.tileConfig(player, build, target)
    }

    private fun shortsDiode(b: Building): Boolean {
        val graph = b.power.graph
        return b.team.data().buildings.contains { d ->
            d is PowerDiode.PowerDiodeBuild && d.back()?.power?.graph == graph && d.front()?.power?.graph == graph
        }
    }

    private fun connectedWithout(a: Building, b: Building): Boolean {
        if (a.power.graph != b.power.graph) return false
        val seen = IntSet()
        val queue = Seq<Building>()
        val conns = Seq<Building>()
        seen.add(b.pos())
        queue.add(b)
        var i = 0
        while (i < queue.size) {
            val cur = queue.get(i++)
            for (o in cur.getPowerConnections(conns)) {
                if ((cur == a && o == b) || (cur == b && o == a)) continue
                if (o == a) return true
                if (seen.add(o.pos())) queue.add(o)
            }
        }
        return false
    }
}
