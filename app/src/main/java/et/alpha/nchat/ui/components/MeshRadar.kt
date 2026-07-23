package et.alpha.nchat.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import et.alpha.nchat.ui.theme.MonoSmall
import et.alpha.nchat.ui.theme.MotionDurationMs
import et.alpha.nchat.ui.theme.NChatTheme
import et.alpha.nchat.ui.theme.rememberAnimationsEnabled
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * One peer as the radar draws it.
 *
 * Deliberately not `PeerRow`: the radar knows about distance and signal and
 * nothing about conversations, and a component that cannot see the unread count
 * cannot accidentally start drawing with it.
 *
 * @param id hex peer id. Only ever used to pick a stable bearing — the radar
 *   draws no text, so a full id never reaches the screen.
 * @param hops links between here and this peer, 1 for a direct neighbour, or
 *   null when the distance is unknown. Anything past [HopRings] and anything
 *   unknown is drawn out on the dashed edge ring rather than being rounded down
 *   to a distance this device cannot actually claim.
 * @param signalLevel bars of link quality, `0..4`, which sets the node's size.
 * @param lastRelayAt epoch millis of the last packet across this peer's link, or
 *   null if none. Drives the pulse.
 */
@Immutable
data class RadarPeer(
    val id: String,
    val hops: Int?,
    val signalLevel: Int,
    val isOnline: Boolean,
    val lastRelayAt: Long? = null,
)

/**
 * The mesh as concentric rings: this device at the centre, peers placed on the
 * ring for their hop count.
 *
 * Proximity is the primary information in this app, and this is the screen that
 * says so — the ring a node sits on *is* how far away it is, and its size is how
 * well it can be heard. A link line is drawn only to peers this device is
 * connected to directly. Everything past one hop is deliberately unconnected on
 * screen: this device sees its own links and nothing else, so drawing a line
 * from here to a three-hop peer would invent a relay chain nobody observed.
 *
 * ## Animation
 *
 * Not `rememberInfiniteTransition`, which the design notes suggest: an infinite
 * transition runs at display refresh rate for as long as it is composed, and
 * neither of the two things this app needs — a 30fps ceiling and a hard stop
 * when the screen is not resumed — can be imposed on it. So the radar drives
 * itself from one frame clock, and the whole drawing is a pure function of that
 * one sample.
 *
 * The clock runs only while there is something to animate: an empty mesh sweeps,
 * a peer that has just relayed a packet pulses for [RelayPulseMs], and a node
 * that has just arrived or left is settling. A radar with peers on it and no
 * traffic is a still image and costs nothing to keep on screen, which for an app
 * people run all day on a phone they cannot charge is the case worth optimising.
 *
 * @param animate false disables motion outright — battery saver, or the system
 *   animation scale set to zero. The radar still draws, just without the sweep
 *   and with every node at full alpha.
 */
@Composable
fun MeshRadar(
    peers: List<RadarPeer>,
    modifier: Modifier = Modifier,
    animate: Boolean = rememberAnimationsEnabled(),
) {
    var frame by remember { mutableStateOf(RadarFrame(0L, System.currentTimeMillis())) }
    val resumed = rememberIsResumed()
    val nodes = radarNodes(peers, frame.elapsedMs, animate)

    // Read off the last frame rather than off the wall clock directly, so this
    // stays a function of state that recomposition can see. While the clock is
    // stopped the sample is stale, which resolves itself: a stale sample can
    // only read as "a packet just arrived", which starts the clock, which
    // immediately produces a fresh sample.
    val relaying = peers.any { it.isRelaying(frame.wallClockMs) }
    // A node part-way through arriving or leaving is measured against the same
    // frozen sample, and starts the clock for the same reason.
    val settling = animate && nodes.any { it.progressAt(frame.elapsedMs, animate) < 1f }
    val running = animate && resumed && (peers.isEmpty() || relaying || settling)

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        // Elapsed time accumulates rather than being measured from a start
        // instant, so time spent paused is not time the animation advanced
        // through: the sweep resumes from the bearing it stopped at.
        var previous = withFrameMillis { it }
        while (true) {
            // Sleeping before asking for a frame, rather than dropping frames we
            // asked for, is what makes the cap cheap — between samples there is
            // no frame callback registered at all. The cost is that the next
            // sample lands on the first vsync after the budget expires, so the
            // real rate is 30fps on a 30Hz display and a little under it on
            // anything faster. Every animation here is a function of the frame's
            // own timestamp, so uneven pacing changes the frame count and not
            // what the user sees.
            delay(FrameIntervalMs)
            withFrameMillis { now ->
                frame = RadarFrame(
                    elapsedMs = frame.elapsedMs + (now - previous),
                    wallClockMs = System.currentTimeMillis(),
                )
                previous = now
            }
        }
    }

    val ringColor = MaterialTheme.colorScheme.outlineVariant
    val dimColor = MaterialTheme.colorScheme.outline
    val liveColor = MaterialTheme.colorScheme.primary
    val selfColor = MaterialTheme.colorScheme.secondary

    // The radar is a picture of something that is also listed as text below it,
    // so a screen reader gets the summary and then the rows themselves.
    val description = remember(peers) { peers.describe() }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(RadarHeight)
                .semantics { contentDescription = description },
        ) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = min(size.width, size.height) / 2f - EdgePadding.toPx()
            if (maxRadius <= 0f) return@Canvas

            drawRings(centre, maxRadius, ringColor)
            if (peers.isEmpty()) {
                drawSweep(centre, maxRadius, sweepDegrees(frame.elapsedMs), liveColor)
            }
            drawPeers(
                nodes = nodes,
                centre = centre,
                maxRadius = maxRadius,
                elapsedMs = frame.elapsedMs,
                wallClockMs = frame.wallClockMs,
                animate = animate,
                pulse = pulseAlpha(frame.elapsedMs),
                liveColor = liveColor,
                dimColor = dimColor,
            )
            drawSelf(centre, selfColor)
        }

        // Rings without a key are just decoration. One line of machine text says
        // what the geometry means, and it is a real `Text` rather than something
        // drawn into the canvas so that it scales with the user's font setting.
        if (peers.isNotEmpty()) {
            Text(
                text = "rings = hops away",
                style = MonoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * A node the radar is drawing, and where it is in arriving or leaving.
 *
 * @param peer the peer's last known state. Kept after the peer has gone from the
 *   roster, for as long as the exit takes to play.
 * @param startedAt animation-clock millis the current transition began at, on the
 *   same clock as [RadarFrame.elapsedMs] so that a transition does not advance
 *   while the screen is paused.
 */
@Immutable
private data class RadarNode(val peer: RadarPeer, val startedAt: Long, val leaving: Boolean)

/**
 * The nodes to draw: the peers passed in, plus ones that have just left and are
 * still finishing their exit.
 *
 * The same problem the peer list has, one layer down — by the time a departure
 * would animate, the peer is already out of the list and there is nothing left to
 * fade. So departures are held here until the clock says their exit has finished.
 * Holding one for 200ms is not a claim that the peer is still in range: it is
 * shrinking and disappearing, which is a picture of exactly what happened.
 *
 * Peers already present when the radar first composes are settled rather than
 * arriving. They are not appearing — they were already there, and the radar is
 * what appeared. It also means scrolling the radar off screen and back does not
 * replay every entrance.
 */
@Composable
private fun radarNodes(peers: List<RadarPeer>, elapsedMs: Long, animate: Boolean): List<RadarNode> {
    val drawn = remember {
        mutableStateMapOf<String, RadarNode>().apply {
            peers.forEach { put(it.id, RadarNode(it, startedAt = SettledStart, leaving = false)) }
        }
    }

    LaunchedEffect(peers) {
        val ids = peers.mapTo(mutableSetOf()) { it.id }
        peers.forEach { peer ->
            val existing = drawn[peer.id]
            drawn[peer.id] = when {
                // A peer that walked back in while fading out enters again
                // rather than reversing: it did leave, and the radar saying so
                // is more useful than the animation being tidy.
                existing == null || existing.leaving ->
                    RadarNode(peer = peer, startedAt = elapsedMs, leaving = false)
                // Already on screen — take the new reading, keep the entrance.
                else -> existing.copy(peer = peer)
            }
        }
        // Copied out of the map before mutating it.
        drawn.keys.filterNot { it in ids }.forEach { id ->
            val node = drawn.getValue(id)
            if (!node.leaving) drawn[id] = node.copy(startedAt = elapsedMs, leaving = true)
        }
    }

    // Dropped when the animation says the exit is over, rather than on a timer
    // started alongside it: the two could disagree, and on this clock they would,
    // because a paused screen stops the animation and would not stop the timer.
    val finished = drawn.values
        .filter { it.leaving && it.progressAt(elapsedMs, animate) >= 1f }
        .map { it.peer.id }
    LaunchedEffect(finished) { finished.forEach { drawn.remove(it) } }

    // Stable draw order, so overlapping nodes do not swap which is on top.
    return drawn.values.sortedBy { it.peer.id }
}

/** How far through its entrance or exit a node is, `0..1`. */
private fun RadarNode.progressAt(elapsedMs: Long, animate: Boolean): Float =
    if (!animate) 1f else ((elapsedMs - startedAt).toFloat() / MotionDurationMs).coerceIn(0f, 1f)

/**
 * One sample of the radar's clock.
 *
 * Two clocks, because they answer different questions. [elapsedMs] is how long
 * the animation has actually been running and drives the phase of anything
 * cyclic; [wallClockMs] is what relay timestamps are comparable against. Sampled
 * together so that everything drawn in a frame agrees about when that frame is.
 */
@Immutable
private data class RadarFrame(val elapsedMs: Long, val wallClockMs: Long)

/**
 * Whether the host lifecycle is resumed.
 *
 * The design system's rule is "do not animate anything while the app is
 * backgrounded", and STARTED is not good enough for it — a screen behind a
 * transparent dialog or in the paused half of split screen is visible, still
 * composed, and still nobody's foreground.
 */
@Composable
private fun rememberIsResumed(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = event.targetState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return resumed
}

/** [HopRings] solid rings, then a dashed one for "further than we can count". */
private fun DrawScope.drawRings(centre: Offset, maxRadius: Float, color: Color) {
    val stroke = Stroke(width = RingWidth.toPx())
    repeat(HopRings) { ring ->
        drawCircle(color = color, radius = ringRadius(ring + 1, maxRadius), center = centre, style = stroke)
    }
    drawCircle(
        color = color,
        radius = maxRadius,
        center = centre,
        style = Stroke(
            width = RingWidth.toPx(),
            pathEffect = PathEffect.dashPathEffect(
                intervals = floatArrayOf(DashOn.toPx(), DashOff.toPx()),
                phase = 0f,
            ),
        ),
    )
}

/**
 * The empty state: a hand sweeping the rings, fading out towards the centre.
 *
 * Motion is the whole message here — an empty mesh is a radio that is looking,
 * not an app that has failed, so the screen has to be visibly doing something.
 */
private fun DrawScope.drawSweep(centre: Offset, maxRadius: Float, degrees: Float, color: Color) {
    val tip = Offset(centre.x, centre.y - maxRadius)
    rotate(degrees = degrees, pivot = centre) {
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(color.copy(alpha = 0f), color.copy(alpha = SweepAlpha)),
                start = centre,
                end = tip,
            ),
            start = centre,
            end = tip,
            strokeWidth = SweepWidth.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawPeers(
    nodes: List<RadarNode>,
    centre: Offset,
    maxRadius: Float,
    elapsedMs: Long,
    wallClockMs: Long,
    animate: Boolean,
    pulse: Float,
    liveColor: Color,
    dimColor: Color,
) {
    nodes.forEach { node ->
        val peer = node.peer
        // Fade and scale, never slide: the ring a node sits on is a statement
        // about distance, so a node has to arrive at its distance rather than
        // travel to it.
        val progress = FastOutSlowInEasing.transform(node.progressAt(elapsedMs, animate))
        val presence = if (node.leaving) 1f - progress else progress
        if (presence <= 0f) return@forEach
        val scale = if (node.leaving) {
            1f - (1f - ExitScale) * progress
        } else {
            EnterScale + (1f - EnterScale) * progress
        }

        val radius = ringRadius(ringFor(peer.hops), maxRadius)
        val bearing = bearingFor(peer.id)
        val position = Offset(
            x = centre.x + radius * cos(bearing),
            y = centre.y + radius * sin(bearing),
        )
        // Gentle and only when a packet has just crossed this link. A node that
        // breathes constantly is decoration; one that only moves when something
        // happened is an instrument reading.
        val alpha = presence * if (peer.isRelaying(wallClockMs)) pulse else 1f

        if (peer.isOnline && peer.hops == DirectHops) {
            drawLine(
                color = dimColor.copy(alpha = LinkAlpha * alpha),
                start = centre,
                end = position,
                strokeWidth = LinkWidth.toPx(),
            )
        }
        val color = if (peer.isOnline) liveColor else dimColor
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = nodeRadius(peer.signalLevel).toPx() * scale,
            center = position,
            // Hollow for a peer whose distance is a guess, so the edge ring does
            // not read as a confident "this far away and no further".
            style = if (peer.hops == null) Stroke(width = RingWidth.toPx()) else Fill,
        )
    }
}

/** This device. Bright, because it is the one node that is definitely live. */
private fun DrawScope.drawSelf(centre: Offset, color: Color) {
    drawCircle(color = color.copy(alpha = SelfHaloAlpha), radius = SelfHalo.toPx(), center = centre)
    drawCircle(color = color, radius = SelfRadius.toPx(), center = centre)
}

private fun RadarPeer.isRelaying(wallClockMs: Long): Boolean =
    lastRelayAt != null && wallClockMs - lastRelayAt <= RelayPulseMs

/** Ring index, `1..HopRings + 1`, the last of which is the dashed edge. */
private fun ringFor(hops: Int?): Int =
    if (hops == null || hops > HopRings) HopRings + 1 else hops.coerceAtLeast(DirectHops)

/**
 * Where a ring sits, evenly spaced across the band between the centre node and
 * the edge.
 *
 * The band starts at [InnerRadius] rather than at zero. Dividing the whole radius
 * evenly was the first attempt and it put the one-hop ring underneath this
 * device's own node, which is the single most important ring on the screen —
 * a direct neighbour ended up drawn on top of the centre with its link line too
 * short to see.
 */
private fun DrawScope.ringRadius(ring: Int, maxRadius: Float): Float {
    val inner = InnerRadius.toPx()
    return inner + (maxRadius - inner) * ring / (HopRings + 1)
}

/**
 * Where on its ring a peer sits, in radians clockwise from twelve o'clock.
 *
 * Derived from the id rather than from the peer's index in the list, because
 * position on the radar carries meaning: a bearing assigned by index would spin
 * every other node around the moment someone new walked into range. `hashCode`
 * on a String is specified, so the same peer keeps the same bearing between runs
 * as well as between frames. Two peers can collide on one ring; the list below
 * is what disambiguates them, and a radar is allowed to look like a radar.
 */
private fun bearingFor(id: String): Float {
    val step = ((id.hashCode() % BearingSteps) + BearingSteps) % BearingSteps
    return (step.toFloat() / BearingSteps) * TwoPi - TwoPi / 4f
}

/** Node size, from smallest at no signal to largest at full bars. */
private fun nodeRadius(signalLevel: Int): Dp =
    NodeRadiusMin + (NodeRadiusMax - NodeRadiusMin) * (signalLevel.coerceIn(0, SignalMax) / SignalMax.toFloat())

/** Full alpha at rest, dipping to [PulseFloor] and back once per cycle. */
private fun pulseAlpha(elapsedMs: Long): Float {
    val phase = (elapsedMs % PulsePeriodMs).toFloat() / PulsePeriodMs
    return PulseFloor + (1f - PulseFloor) * (0.5f + 0.5f * cos(phase * TwoPi))
}

private fun sweepDegrees(elapsedMs: Long): Float =
    (elapsedMs % SweepPeriodMs).toFloat() / SweepPeriodMs * 360f

private fun List<RadarPeer>.describe(): String {
    if (isEmpty()) return "mesh radar, searching for peers"
    val direct = count { it.hops == DirectHops }
    val relayed = size - direct
    return buildString {
        append("mesh radar, $size peers")
        if (direct > 0) append(", $direct one hop away")
        if (relayed > 0) append(", $relayed reached through relays")
    }
}

private val RadarHeight = 200.dp

/** Room outside the widest ring for the nodes sitting on it. */
private val EdgePadding = 14.dp

/** Clearance around this device's own node, before the first hop ring. */
private val InnerRadius = 20.dp

private val RingWidth = 1.dp
private val DashOn = 3.dp
private val DashOff = 5.dp
private val LinkWidth = 1.dp
private val NodeRadiusMin = 3.5.dp
private val NodeRadiusMax = 7.dp
private val SelfRadius = 5.dp
private val SelfHalo = 11.dp
private val SweepWidth = 2.dp

private const val LinkAlpha = 0.55f
private const val SelfHaloAlpha = 0.15f
private const val SweepAlpha = 0.35f
private const val PulseFloor = 0.55f

/** Scales for a node arriving and leaving, matching the peer list's rows. */
private const val EnterScale = 0.9f
private const val ExitScale = 0.95f

/** A `startedAt` far enough back that the transition is over before it begins. */
private const val SettledStart = Long.MIN_VALUE / 2

/** Rings drawn with a hop count on them. Past this, peers go on the dashed edge. */
private const val HopRings = 3
private const val DirectHops = 1
private const val SignalMax = 4

/** Distinct bearings a peer id can hash onto — one per degree. */
private const val BearingSteps = 360

private const val TwoPi = (2 * PI).toFloat()

/** 30fps, the ceiling this component is not allowed to exceed. */
private const val FrameIntervalMs = 33L

/** How long a node keeps pulsing after a packet crossed its link. */
private const val RelayPulseMs = 1_500L

/** One breath of the relay pulse. Slow enough to read as breathing. */
private const val PulsePeriodMs = 900L

/** One full turn of the empty-state sweep. */
private const val SweepPeriodMs = 4_000L

@Preview(showBackground = true, backgroundColor = 0xFF0E1A26)
@Composable
private fun MeshRadarPreview() {
    NChatTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            MeshRadar(
                modifier = Modifier.padding(vertical = 12.dp),
                peers = listOf(
                    // A direct neighbour: connected, full bars, and relaying
                    // right now — the only node with a link line to the centre.
                    RadarPeer(
                        id = "9e8b7a65",
                        hops = 1,
                        signalLevel = 4,
                        isOnline = true,
                        lastRelayAt = System.currentTimeMillis(),
                    ),
                    // Two hops out, reachable only through somebody else, so
                    // there is no signal reading to draw and no link to show.
                    RadarPeer(id = "44ff0011", hops = 2, signalLevel = 0, isOnline = false),
                    RadarPeer(id = "c0ffee12", hops = 3, signalLevel = 0, isOnline = false),
                ),
            )
        }
    }
}

/**
 * A peer walking in and out of range every couple of seconds, so the entrance
 * and exit can be watched without a second phone in the room.
 *
 * Still in Studio's preview pane, which does not run effects — deploy it to a
 * device to see it move.
 */
@Preview(showBackground = true, backgroundColor = 0xFF0E1A26)
@Composable
private fun MeshRadarComingAndGoingPreview() {
    var inRange by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            inRange = !inRange
        }
    }
    NChatTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            MeshRadar(
                modifier = Modifier.padding(vertical = 12.dp),
                peers = buildList {
                    add(RadarPeer("9e8b7a65", hops = 1, signalLevel = 4, isOnline = true))
                    if (inRange) {
                        add(RadarPeer("44ff0011", hops = 2, signalLevel = 0, isOnline = false))
                    }
                },
            )
        }
    }
}

/** Nobody in range, which is how the app starts every time. */
@Preview(showBackground = true, backgroundColor = 0xFF0E1A26)
@Composable
private fun MeshRadarEmptyPreview() {
    NChatTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            MeshRadar(peers = emptyList(), modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}
