package com.ironlog.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

val Bg = Color(0xFF000000)
val Card = Color(0xFF1C1C1E)
val Card2 = Color(0xFF2C2C2E)
/** The app's accent colour (Settings → App accent colour). Everything that's "blue" in the app uses it. */
object Accent {
    val default = Color(0xFF2D8CFF)
    var color by androidx.compose.runtime.mutableStateOf(default)
}
val Blue: Color get() = Accent.color
val Sub = Color(0xFF8E8E93)
val Red = Color(0xFFFF453A)
val Green = Color(0xFF30D158)

/** Where you are in the app. */
sealed interface Route {
    data class Tabs(val tab: Int) : Route
    data class RoutineView(val id: String) : Route
    data class RoutineEdit(val id: String?) : Route
    /** [confirm]: the button text when picking several (default "Add N exercises"). */
    class Picker(val title: String, val multi: Boolean, val confirm: String? = null, val only: ((Exercise) -> Boolean)? = null, val onPick: (List<String>) -> Unit) : Route
    data class ExerciseDetail(val id: String) : Route
    data class EditExercise(val id: String?, val copyOf: String? = null) : Route
    data object Active : Route
    data class Summary(val id: String) : Route
    data class ShareWorkout(val id: String) : Route
    data class WorkoutDetail(val id: String) : Route
    data object Settings : Route
    data object Library : Route
    data object HevyMatch : Route
    /** [afterTest]: opened by finishing a 1 Rep Max test; back goes home with the 1 Rep Max exit. */
    data class RankDetail(val id: String, val afterTest: Boolean = false) : Route
    data object OneRmList : Route
    data object RankHelp : Route
    data object AllWorkouts : Route
    data object Bin : Route
    data class ExerciseHistory(val id: String) : Route
    data object Share : Route
    data class OneRmTest(val id: String) : Route
    data object Research : Route
    data object Reference : Route
}

object Nav {
    val stack = mutableStateListOf<Route>(Route.Tabs(0))
    /** A unique key per screen in [stack], so each screen keeps its state (like a search) while you're deeper in. */
    private val keys = mutableListOf(0L)
    private var next = 1L
    /** Keys of closed screens, whose saved state can be thrown away. */
    val closed = mutableListOf<Long>()
    val top get() = stack.last()
    val topKey get() = keys.last()
    fun go(r: Route) {
        // Going into 1 Rep Max or a test (not coming back to it) plays their effects once.
        if (r == Route.OneRmList && top != Route.OneRmList) OneRmFx.enteringList = true
        if (r is Route.OneRmTest) OneRmFx.enteringTest = true
        keys += next++; stack += r
    }
    fun back() {
        // Leaving 1 Rep Max by throwing it away leaves a few embers behind.
        if (stack.size > 1 && isOneRm(top) && extraAnims() && (OneRmFx.leave == 0 || OneRmFx.leave == 2)) LeaveTrail.fire(OneRmFx.leave == 0)
        if (stack.size > 1) { closed += keys.removeAt(keys.lastIndex); stack.removeAt(stack.lastIndex) } }
    /** From the rank screen after a 1 Rep Max test: straight home, leaving with the 1 Rep Max exit and its embers. */
    fun leaveTestToHome() {
        if (stack.size > 1 && isOneRm(top) && extraAnims() && (OneRmFx.leave == 0 || OneRmFx.leave == 2)) LeaveTrail.fire(OneRmFx.leave == 0)
        home(0)
    }
    fun home(tab: Int) { closed += keys; keys.clear(); keys += next++; stack.clear(); stack += Route.Tabs(tab) }
    fun replace(r: Route) { if (stack.isNotEmpty()) { closed += keys.removeAt(keys.lastIndex); stack.removeAt(stack.lastIndex) }; keys += next++; stack += r }
}

class MainActivity : ComponentActivity() {
    // Any touch in the app (tap, scroll, swipe) counts as activity for the workout's active time.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN || ev.actionMasked == android.view.MotionEvent.ACTION_MOVE) Workouts.touched()
        return super.dispatchTouchEvent(ev)
    }

    override fun onStart() { super.onStart(); AppLock.onStart() }
    override fun onStop() { super.onStop(); AppLock.onStop() }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("openWorkout", false) && Store.active.value != null && Nav.top != Route.Active) Nav.go(Route.Active)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StandardsData.init(this)
        OneRm.init(this)
        Store.load(this)
        BodyMap.init(this)
        Rest.ensureChannel(this)
        WorkoutService.start(this)
        if (intent?.getBooleanExtra("openWorkout", false) == true && Store.active.value != null) Nav.go(Route.Active)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Blue, onPrimary = Color.White, background = Bg, surface = Bg,
                    surfaceVariant = Card, onSurfaceVariant = Sub, surfaceContainerHigh = Card2,
                    surfaceContainer = Card, error = Red,
                ),
            ) {
                Surface(Modifier.fillMaxSize(), color = Bg) { Root() }
            }
        }
    }
}

@Composable
private fun Root() {
    BackHandler(enabled = Nav.stack.size > 1) { Nav.back() }
    val route = Nav.top
    val key = Nav.topKey
    val saved = rememberSaveableStateHolder()
    LaunchedEffect(key) { Nav.closed.forEach { saved.removeState(it) }; Nav.closed.clear() }
    // Tapping outside a text box or scrolling with your finger puts the keyboard away.
    val focus = LocalFocusManager.current
    val hideOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available != Offset.Zero) focus.clearFocus()
                return Offset.Zero
            }
        }
    }
    Column(
        Modifier.fillMaxSize().background(Bg)
            // Watches every tap before buttons and rows handle it, so tapping an exercise or a button closes the keyboard too.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val up = waitForUpOrCancellation(PointerEventPass.Initial)
                    if (up != null && !TextBoxes.contains(up.position)) focus.clearFocus()
                }
            }
            .nestedScroll(hideOnScroll),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
          // Each screen change slides (forward in, back out), tabs cross-fade, and 1 Rep Max screens slam in.
          AnimatedContent(
            Screen(key, route, Nav.stack.size),
            Modifier.fillMaxSize(),
            transitionSpec = { screenTransition(initialState, targetState) },
            contentKey = { it.key },
            label = "screen",
          ) { sc ->
            CompositionLocalProvider(LocalScreenKey provides sc.key) {
              Box(Modifier.fillMaxSize().background(Bg)) {
                saved.SaveableStateProvider(sc.key) { RouteScreen(sc.route) }
              }
            }
          }
          LeaveTrail.Overlay()
        }
        val active by Store.active.collectAsState()
        // On tab screens the bar sits above the tab bar (it's part of the tab screen); elsewhere at the very bottom.
        if (active != null && route != Route.Active && route !is Route.Tabs) ResumeBar(Modifier.navigationBarsPadding())
    }
    Onboarding()
    RankUpOverlay()
    AppTextPill()
    WipedScreen()
    LockScreen()
}

@Composable
private fun RouteScreen(route: Route) {
    // Developer text edits apply per screen (see AppText).
    val textScope = when (route) { is Route.Tabs -> "Tab${route.tab}"; else -> route::class.simpleName ?: "" }
    androidx.compose.runtime.CompositionLocalProvider(LocalTextScope provides textScope) {
    when (route) {
        // The tab bar belongs to the tab screens, so it moves with them; the screen area never changes size
        // mid-transition (which made leaving screens jump for a frame or two).
        is Route.Tabs -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (route.tab) {
                    0 -> HomeScreen()
                    1 -> WorkoutTab()
                    else -> ProfileScreen()
                }
            }
            ResumeBar()
            BottomBar(route.tab)
        }
        is Route.RoutineView -> RoutineViewScreen(route.id)
        is Route.RoutineEdit -> RoutineEditScreen(route.id)
        is Route.Picker -> PickerScreen(route)
        is Route.ExerciseDetail -> ExerciseDetailScreen(route.id)
        is Route.EditExercise -> EditExerciseScreen(route.id, route.copyOf)
        Route.Active -> ActiveWorkoutScreen()
        is Route.Summary -> SummaryScreen(route.id)
        is Route.ShareWorkout -> WorkoutShareScreen(route.id)
        is Route.WorkoutDetail -> WorkoutDetailScreen(route.id)
        Route.Settings -> SettingsScreen()
        Route.Library -> PickerScreen(null)
        Route.HevyMatch -> HevyMatchScreen()
        is Route.RankDetail -> RankDetailScreen(route.id, route.afterTest)
        Route.OneRmList -> OneRmListScreen()
        Route.RankHelp -> RankHelpScreen()
        Route.AllWorkouts -> AllWorkoutsScreen()
        Route.Bin -> RecycleBinScreen()
        is Route.ExerciseHistory -> ExerciseHistoryScreen(route.id)
        Route.Share -> ShareScreen()
        is Route.OneRmTest -> OneRmTestScreen(route.id)
        Route.Research -> ResearchScreen()
        Route.Reference -> ReferenceScreen()
    }
    }
}

/** The screen on show: its key, where it goes, and how deep it is (to tell going forward from going back). */
private data class Screen(val key: Long, val route: Route, val depth: Int)

/** The key of the screen a composable is part of. */
val LocalScreenKey = staticCompositionLocalOf { -1L }

/** Closes this screen when what it shows is gone – unless it's already on its way out (sliding away after Back). */
@Composable
fun closeScreen() {
    val k = LocalScreenKey.current
    LaunchedEffect(k) { if (Nav.topKey == k) Nav.back() }
}

fun isOneRm(r: Route) = r is Route.OneRmTest || r == Route.OneRmList || (r is Route.RankDetail && r.afterTest)

private fun screenTransition(from: Screen, to: Screen): ContentTransform {
    val ease = FastOutSlowInEasing
    return when {
        // Tabs: a quick cross-fade.
        from.route is Route.Tabs && to.route is Route.Tabs ->
            fadeIn(tween(200, easing = ease)) togetherWith fadeOut(tween(140))
        // Into 1 Rep Max: the new screen drops in from slightly too big, like a plate hitting the floor.
        // Leaving a 1 Rep Max screen: the chosen exit (thrown upward by default), never any shaking or fire.
        to.depth < from.depth && isOneRm(from.route) && extraAnims() -> OneRmFx.leaving()
        // Into 1 Rep Max from elsewhere: the chosen opening. From its list into a test: the chosen test opening.
        to.depth > from.depth && to.route == Route.OneRmList && !isOneRm(from.route) && extraAnims() -> OneRmFx.enter()
        // A test opens the same way.
        to.depth > from.depth && to.route is Route.OneRmTest && extraAnims() -> OneRmFx.enter()
        // Forward: slides in from the right over the old one, which drifts left and dims.
        to.depth > from.depth ->
            (slideInHorizontally(tween(300, easing = ease)) { it / 3 } + fadeIn(tween(220))) togetherWith
                (slideOutHorizontally(tween(300, easing = ease)) { -it / 8 } + fadeOut(tween(260)))
        // Back: the reverse.
        to.depth < from.depth ->
            (slideInHorizontally(tween(300, easing = ease)) { -it / 8 } + fadeIn(tween(240))) togetherWith
                (slideOutHorizontally(tween(260, easing = ease)) { it / 3 } + fadeOut(tween(200)))
        // Swapped in place (e.g. Finish → Summary): fade through.
        else -> fadeIn(tween(240, delayMillis = 60)) togetherWith fadeOut(tween(160))
    }.apply { targetContentZIndex = if (to.depth >= from.depth) 1f else if (isOneRm(from.route) && extraAnims()) -1f else 0f }
}

@Composable
private fun ResumeBar(pad: Modifier = Modifier) {
    val a = Store.active.collectAsState().value ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    Row(
        Modifier.fillMaxWidth().background(Card2).clickable { Nav.go(Route.Active) }.then(pad).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Workout in progress", fontWeight = FontWeight.Bold)
            Text(a.name + if (a.start > 0) " · " + Units.clock(now - a.start) else " · not started", color = Sub, fontSize = 13.sp)
        }
        Text("Resume", color = Blue, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BottomBar(tab: Int) {
    HorizontalDivider(color = Card2)
    Row(Modifier.fillMaxWidth().background(Card).navigationBarsPadding().padding(vertical = 6.dp)) {
        TabItem("Home", "🏠", tab == 0) { Nav.home(0) }
        TabItem("Workout", "🏋", tab == 1) { Nav.home(1) }
        TabItem("Settings", "⚙️", tab == 2) { Nav.home(2) }
    }
}

@Composable
private fun RowScope.TabItem(label: String, icon: String, selected: Boolean, onClick: () -> Unit) {
    val press = rememberPress(0.9f)
    // The highlight fades and the icon pops a little when a tab is picked.
    val glow by androidx.compose.animation.animateColorAsState(if (selected) Blue.copy(alpha = 0.18f) else Color.Transparent, tween(220), label = "tab")
    val tint by androidx.compose.animation.animateColorAsState(if (selected) Blue else Color.White, tween(220), label = "tabText")
    val pop by androidx.compose.animation.core.animateFloatAsState(if (selected) 1.08f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = androidx.compose.animation.core.Spring.StiffnessMedium), label = "tabPop")
    Column(
        Modifier.weight(1f).pressScale(press).pressClick(press, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.graphicsLayer { scaleX = pop; scaleY = pop }.clip(RoundedCornerShape(16.dp)).background(glow)
                .padding(horizontal = 18.dp, vertical = 2.dp),
        ) { Text(icon, fontSize = 20.sp, color = tint) }
        Text(label, fontSize = 12.sp, color = tint)
    }
}

// ---------- shared pieces ----------

/** Top bar like the reference screens: back arrow, centred title, optional action. */
@Composable
fun TopBar(title: String, back: Boolean = true, action: String? = null, onBack: () -> Unit = { Nav.back() }, lead: String? = null, onLead: () -> Unit = {},
           extra: (@Composable () -> Unit)? = null, onAction: () -> Unit = {}) {
    Box(Modifier.fillMaxWidth().background(Card).statusBarsPadding().height(56.dp)) {
        if (back) Text(
            "←", fontSize = 26.sp, modifier = Modifier.align(Alignment.CenterStart).clickable(onClick = onBack).padding(horizontal = 16.dp),
        ) else if (lead != null) Text(
            lead, color = Blue, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterStart).clickable(onClick = onLead).padding(horizontal = 18.dp, vertical = 8.dp),
        )
        // Long titles shrink to two lines and never run under the back arrow or the action.
        val actionW = if (action != null) action.length * 10 + 36 else 0
        val side = maxOf(56, actionW) + if (extra != null) 40 else 0
        val long = title.length > 24
        Text(
            title, fontSize = if (long) 15.sp else 19.sp, lineHeight = if (long) 18.sp else 24.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = side.dp),
        )
        if (action != null) Text(
            action, color = Blue, fontSize = 17.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.CenterEnd).clickable(onClick = onAction).padding(horizontal = 16.dp),
        )
        // A small icon button left of the action (e.g. Reference Mode).
        if (extra != null) Box(Modifier.align(Alignment.CenterEnd).padding(end = maxOf(8, actionW - 8).dp)) { extra() }
    }
}

@Composable
fun BigButton(text: String, color: Color = Blue, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    val press = rememberPress()
    Button(
        onClick = onClick, modifier = Modifier.pressScale(press).then(modifier).height(50.dp), shape = RoundedCornerShape(10.dp),
        interactionSource = press.source,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
    ) { OutlinedLabel(text, fontSize = 16.sp) }
}

/**
 * White text with a thin black outline, for labels on coloured buttons (accent, green, red), so it stays easy to
 * read whatever the colour.
 */
@Composable
fun OutlinedLabel(text: String, fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier, fontWeight: FontWeight = FontWeight.Bold,
                  color: Color = Color.White) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, fontSize = fontSize, fontWeight = fontWeight, maxLines = 1, softWrap = false, color = Color.Black,
            style = androidx.compose.material3.LocalTextStyle.current.copy(drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f,
                join = androidx.compose.ui.graphics.StrokeJoin.Round)))
        Text(text, fontSize = fontSize, fontWeight = fontWeight, maxLines = 1, softWrap = false, color = color)
    }
}

@Composable
fun GreyButton(text: String, modifier: Modifier = Modifier.fillMaxWidth(), textColor: Color = Color.White, onClick: () -> Unit) {
    val press = rememberPress()
    Button(
        onClick = onClick, modifier = Modifier.pressScale(press).then(modifier).height(46.dp), shape = RoundedCornerShape(10.dp),
        interactionSource = press.source,
        colors = ButtonDefaults.buttonColors(containerColor = Card2, contentColor = textColor),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false) }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, color = Sub, fontSize = 15.sp, modifier = modifier.padding(horizontal = 16.dp, vertical = 10.dp))

@Composable
fun Chip(text: String) = Text(
    text, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Card2).padding(horizontal = 8.dp, vertical = 3.dp),
)

@Composable
fun StatBox(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = Sub, fontSize = 13.sp)
        // Always one line: a long value (e.g. a big volume) shrinks to fit instead of wrapping.
        NameText(androidx.compose.ui.text.AnnotatedString(value), Modifier.padding(end = 4.dp), fontSize = 17.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, minScale = 0.5f)
    }
}

/** Header of a set table: SET / LBS / REPS etc. */
@Composable
fun TableHeader(cols: List<Pair<String, Float>>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        cols.forEach { (t, w) -> Text(t, color = Sub, fontSize = 13.sp, modifier = Modifier.weight(w), textAlign = TextAlign.Start) }
    }
}

@Composable
fun ConfirmText(text: String, onYes: () -> Unit, onNo: () -> Unit, yes: String = "Yes") {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onNo,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onYes) { Text(yes, color = Red) } },
        dismissButton = { TextButton(onClick = onNo) { Text("Cancel") } },
    )
}

@Composable
fun Spacer(h: Int) = androidx.compose.foundation.layout.Spacer(Modifier.height(h.dp))

@Composable
fun HSpacer(w: Int) = androidx.compose.foundation.layout.Spacer(Modifier.width(w.dp))

fun columnsFor(t: ExType, withPrevious: Boolean): List<Pair<String, Float>> {
    val prev = if (withPrevious) listOf("PREVIOUS" to 2.2f) else emptyList()
    return listOf("SET" to 0.9f) + prev + when (t) {
        ExType.WEIGHT -> listOf(Units.w.uppercase() to 1.4f, "REPS" to 1.2f)
        ExType.BODYWEIGHT -> listOf("REPS" to 2.6f)
        ExType.TIMED -> listOf("TIME" to 2.6f)
        ExType.CARDIO -> listOf(Units.dist.uppercase() to 1.4f, "TIME" to 1.2f)
        ExType.WEIGHT_TIME -> listOf(Units.w.uppercase() to 1.2f, "TIME" to 1.4f)
    }
}

@Suppress("unused")
private val unusedArrangement = Arrangement.Center
