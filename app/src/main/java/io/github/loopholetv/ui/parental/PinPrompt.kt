package io.github.loopholetv.ui.parental

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.data.parental.LockState
import io.github.loopholetv.data.parental.ParentalLock
import io.github.loopholetv.data.parental.PinCheck
import io.github.loopholetv.ui.cast.BACK_KEYS
import io.github.loopholetv.ui.cast.VOLUME_KEYS
import io.github.loopholetv.ui.settings.focusSoon
import io.github.loopholetv.ui.setup.CODE_LENGTH
import io.github.loopholetv.ui.setup.CodePad
import io.github.loopholetv.ui.setup.FocusBackOnClose
import io.github.loopholetv.ui.setup.OK_KEYS
import io.github.loopholetv.ui.setup.PAD_CANCEL
import io.github.loopholetv.ui.setup.PAD_GRID_HEIGHT
import io.github.loopholetv.ui.setup.PadButton
import io.github.loopholetv.ui.setup.PadKeys
import io.github.loopholetv.ui.setup.Title
import io.github.loopholetv.ui.setup.digitFor
import io.github.loopholetv.ui.setup.PAD_GRID_WIDTH
import io.github.loopholetv.ui.theme.Tokens
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a PIN screen is for. */
enum class PinPurpose {
    /** Type the PIN to unlock. */
    UNLOCK,

    /** Choose a new PIN, typed twice. */
    SET,

    /** Type the current PIN, then choose a new one. */
    CHANGE,
}

internal const val TITLE_ENTER = "Enter PIN"
internal const val TITLE_CHOOSE = "Choose a PIN"
internal const val TITLE_AGAIN = "Type it again"
internal const val TITLE_CURRENT = "Enter your current PIN"
internal const val ERROR_MISMATCH = "PINs don't match"
internal const val ERROR_WRONG = "Wrong PIN"
internal fun waitText(seconds: Int) = "Too many tries. Try again in $seconds s"
internal const val FORGOT_LABEL = "Forgot PIN?"
internal const val CONFIRM_TITLE = "Remove all locks and the PIN?"
internal const val CONFIRM_BODY = "Locked channels will show to everyone."
internal const val REMOVE_LABEL = "Remove"
internal const val CANCEL_LABEL = "Cancel"
internal const val PIN_OVERLAY_TAG = "pin-overlay"
internal fun pinBoxTag(index: Int) = "pin-box-$index"
internal const val FORGOT_TAG = "pin-forgot"
internal const val DOT = "•"

/**
 * Asks for a PIN on a screen of its own, shown by [PinPromptHost]. Only one screen is open at a
 * time: a second [ask] while one is showing waits until the first has finished, then shows its own.
 * Without a [PinPromptHost] in the composition an [ask] waits until its caller is cancelled.
 */
class PinPrompter(internal val lock: ParentalLock) {
    internal class Request(val purpose: PinPurpose) {
        val result = CompletableDeferred<Boolean>()
    }

    private val _current = MutableStateFlow<Request?>(null)
    internal val current: StateFlow<Request?> = _current
    private val one = Mutex()

    /**
     * Suspends until the user is done. True when the PIN was right (unlock), a new PIN was saved
     * (set, change) or the PIN was removed from "Forgot PIN?"; false when cancelled. An unlock with
     * nothing locked (no PIN, or already unlocked) is true at once.
     */
    suspend fun ask(purpose: PinPurpose = PinPurpose.UNLOCK): Boolean {
        if (purpose == PinPurpose.UNLOCK && nothingToUnlock()) return true
        return one.withLock {
            // The screen ahead of this one may have unlocked or removed the PIN.
            if (purpose == PinPurpose.UNLOCK && nothingToUnlock()) return@withLock true
            val request = Request(purpose)
            _current.value = request
            try {
                request.result.await()
            } finally {
                _current.value = null
            }
        }
    }

    /** Sets a PIN when there is none, otherwise asks for it. */
    suspend fun ensurePinThen(): Boolean = ask(if (lock.hasPin()) PinPurpose.UNLOCK else PinPurpose.SET)

    private fun nothingToUnlock() = lock.state.value.let { it is LockState.Unlocked || it is LockState.NoPin }
}

val LocalPinPrompter = staticCompositionLocalOf<PinPrompter> { error("No PinPrompter provided") }

private enum class Step { CURRENT, ENTER, CHOOSE, AGAIN }

/**
 * One PIN screen's state and keys. The digits are only ever drawn as dots and are not logged or kept
 * beyond the entry in progress (the first of two entries is held until the second is typed).
 */
internal class PinPromptModel(
    private val purpose: PinPurpose,
    private val lock: ParentalLock,
    private val scope: CoroutineScope,
    private val finish: (Boolean) -> Unit,
) {
    private var step by mutableStateOf(
        when (purpose) {
            PinPurpose.UNLOCK -> Step.ENTER
            PinPurpose.SET -> Step.CHOOSE
            PinPurpose.CHANGE -> Step.CURRENT
        },
    )
    private var first: String? = null
    private var errorText by mutableStateOf<String?>(null)
    private var busy = false

    /** Seconds left of a "too many tries" wait; 0 when none. */
    var waitLeft by mutableIntStateOf(0)
        private set
    var confirming by mutableStateOf(false)
        private set

    /** The confirm's highlighted button: 0 Remove, 1 Cancel (the safe one, first). */
    var confirmFocus by mutableIntStateOf(1)
        private set
    var forgotFocused by mutableStateOf(false)
        private set

    val pad = CodePad(onCode = ::onCode, onCancel = { finish(false) })

    val title: String
        get() = when (step) {
            Step.CURRENT -> TITLE_CURRENT
            Step.ENTER -> TITLE_ENTER
            Step.CHOOSE -> TITLE_CHOOSE
            Step.AGAIN -> TITLE_AGAIN
        }

    val error: String? get() = if (waitLeft > 0) waitText(waitLeft) else errorText

    /** "Forgot PIN?" is for the steps that ask for the existing PIN, never while choosing one. */
    val forgotAvailable: Boolean get() = step == Step.ENTER || step == Step.CURRENT

    fun startWait(seconds: Int) {
        waitLeft = seconds
        pad.clear()
    }

    fun tick() {
        if (waitLeft > 0) waitLeft--
    }

    private fun onCode(code: String) {
        if (busy || waitLeft > 0) return
        errorText = null
        when (step) {
            Step.ENTER, Step.CURRENT -> verify(code)
            Step.CHOOSE -> {
                first = code
                step = Step.AGAIN
                pad.clear()
            }
            Step.AGAIN -> if (code == first) save(code) else {
                first = null
                step = Step.CHOOSE
                errorText = ERROR_MISMATCH
                pad.clear()
            }
        }
    }

    private fun verify(code: String) {
        busy = true
        scope.launch {
            try {
                when (val r = lock.check(code)) {
                    PinCheck.Ok -> if (purpose == PinPurpose.UNLOCK) finish(true) else {
                        step = Step.CHOOSE
                        pad.clear()
                    }
                    PinCheck.Wrong -> {
                        errorText = ERROR_WRONG
                        pad.clear()
                    }
                    is PinCheck.Wait -> startWait(r.seconds)
                }
            } finally {
                busy = false
            }
        }
    }

    private fun save(code: String) {
        busy = true
        scope.launch {
            try {
                lock.setPin(code)
                finish(true)
            } finally {
                busy = false
            }
        }
    }

    private fun remove() {
        busy = true
        scope.launch {
            try {
                lock.removeAll()
                finish(true)
            } finally {
                busy = false
            }
        }
    }

    private fun blocked() = busy || waitLeft > 0

    private fun press() {
        if (forgotFocused) {
            confirming = true
            confirmFocus = 1
        } else if (!blocked() || pad.focused == PAD_CANCEL) {
            pad.press()
        }
    }

    private fun back() {
        if (confirming) confirming = false else pad.back()
    }

    private fun move(dx: Int, dy: Int) {
        when {
            confirming -> confirmFocus = if (dx != 0 || dy != 0) 1 - confirmFocus else confirmFocus
            forgotFocused -> if (dy < 0) forgotFocused = false
            dy > 0 && pad.focused == PAD_CANCEL && forgotAvailable -> forgotFocused = true
            else -> pad.move(dx, dy)
        }
    }

    private fun confirmPressed() {
        if (confirmFocus == 0) remove() else confirming = false
    }

    private val pressed = mutableSetOf<Int>()

    /**
     * A key while the screen shows; true when taken. Volume stays with the system. Digits and D-pad
     * moves act when pressed; OK and Back when released, and only after a press seen here, so a key
     * held from the screen underneath does nothing.
     */
    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int): Boolean {
        if (keyCode in VOLUME_KEYS) return false
        if (!down) {
            val seen = pressed.remove(keyCode)
            if (seen && keyCode in OK_KEYS) if (confirming) confirmPressed() else press()
            if (seen && keyCode in BACK_KEYS) back()
            return true
        }
        if (repeatCount == 0) pressed += keyCode else if (keyCode !in pressed) return true
        val digit = digitFor(keyCode)
        when {
            digit != null -> if (repeatCount == 0 && !confirming && !blocked()) pad.type(digit)
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> move(0, -1)
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> move(0, 1)
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> move(-1, 0)
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> move(1, 0)
        }
        return true
    }
}

/**
 * The PIN screen over everything, whatever is on: a window of its own with the remote to itself
 * while it shows. Put one above every route; [prompter] is the one whose [PinPrompter.ask] it shows.
 */
@Composable
fun PinPromptHost(
    prompter: PinPrompter = LocalPinPrompter.current,
    screenHasFocus: () -> Boolean = { true },
) {
    val request = prompter.current.collectAsState().value
    FocusBackOnClose(request != null, screenHasFocus)
    if (request == null) return
    val scope = rememberCoroutineScope()
    val model = remember(request) {
        PinPromptModel(request.purpose, prompter.lock, scope) { request.result.complete(it) }
    }
    LaunchedEffect(model.waitLeft > 0) {
        while (model.waitLeft > 0) {
            delay(WAIT_TICK_MS)
            model.tick()
        }
    }
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusSoon(focus) }
        Box(
            Modifier
                .fillMaxSize()
                .testTag(PIN_OVERLAY_TAG)
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    val down = e.type == KeyEventType.KeyDown
                    if (!down && e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                    model.onKey(e.nativeKeyEvent.keyCode, down, e.nativeKeyEvent.repeatCount)
                }
                // Nearly opaque: what is underneath must not show through.
                .background(Color.Black.copy(alpha = 0.98f))
                .padding(horizontal = 48.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (model.confirming) Confirm(model.confirmFocus) else Entry(model)
        }
    }
}

private const val WAIT_TICK_MS = 1_000L

@Composable
private fun Entry(model: PinPromptModel) {
    // Laid out like the setup code screen: the title and boxes level with the digit keys.
    Row(horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.Top) {
        Column(
            Modifier.weight(1f, fill = false).widthIn(max = 520.dp).height(PAD_GRID_HEIGHT),
            verticalArrangement = Arrangement.Center,
        ) {
            Title(model.title, TextAlign.Start)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (i in 0 until CODE_LENGTH) {
                    val filled = i < model.pad.digits.length
                    Box(
                        Modifier
                            .testTag(pinBoxTag(i))
                            .size(width = 72.dp, height = 88.dp)
                            .background(Tokens.Surface, RoundedCornerShape(12.dp))
                            .border(
                                2.dp,
                                if (i == model.pad.digits.length) Tokens.Accent else Tokens.Outline,
                                RoundedCornerShape(12.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Only a dot: the digit itself is never drawn.
                        if (filled) Text(DOT, style = MaterialTheme.typography.displaySmall, color = Tokens.OnBackground)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                model.error.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = Tokens.Error,
                modifier = Modifier.heightIn(min = 28.dp),
            )
        }
        PadKeys(model.pad, cancelHighlighted = !model.forgotFocused) {
            if (model.forgotAvailable) {
                PadButton(FORGOT_LABEL, highlighted = model.forgotFocused, width = PAD_GRID_WIDTH, tag = FORGOT_TAG)
            }
        }
    }
}

@Composable
private fun Confirm(focus: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.widthIn(max = 760.dp),
    ) {
        Title(CONFIRM_TITLE)
        Text(
            CONFIRM_BODY,
            style = MaterialTheme.typography.titleMedium,
            color = Tokens.OnBackground,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PadButton(REMOVE_LABEL, highlighted = focus == 0)
            PadButton(CANCEL_LABEL, highlighted = focus == 1)
        }
    }
}
