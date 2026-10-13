package github.axolotl.aclass.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import github.axolotl.aclass.AutoSaveDraft
import github.axolotl.aclass.AutoSaveState

@Composable
internal fun AutoSaveBinding(draft: AutoSaveDraft<*>) {
    DisposableEffect(draft) {
        draft.attach()
        onDispose { draft.detach() }
    }
}

internal fun Modifier.flushOnBlur(draft: AutoSaveDraft<*>): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged {
        if (focused && !it.isFocused) draft.flush()
        focused = it.isFocused
    }
}

@Composable
internal fun AutoSaveError(state: AutoSaveState<*>, draft: AutoSaveDraft<*>, tag: String) {
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(tag)) }
    if (state.canRetry) TextButton(onClick = draft::retry, modifier = Modifier.testTag("$tag-retry")) { Text("重试") }
}
