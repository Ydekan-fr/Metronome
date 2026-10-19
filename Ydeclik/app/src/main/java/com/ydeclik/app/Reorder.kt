package com.ydeclik.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

/**
 * Glisser-déposer fluide : la ligne saisie suit le doigt pixel par pixel, les autres lignes glissent
 * vers leur nouvelle place, et la ligne relâchée se pose en douceur.
 */
class DragReorderState {
    var draggingKey by mutableStateOf<Any?>(null)
    var offset by mutableFloatStateOf(0f)
}

@Composable
fun rememberDragReorderState(): DragReorderState = remember { DragReorderState() }

/** À poser sur la poignée de la ligne. [rowPx] = hauteur d'une ligne + espacement, en pixels (lignes de hauteur fixe). */
@Composable
fun Modifier.reorderHandle(
    state: DragReorderState,
    key: Any,
    rowPx: Float,
    index: Int,
    lastIndex: Int,
    onMove: (from: Int, to: Int) -> Unit
): Modifier {
    val curIndex by rememberUpdatedState(index)
    val curLast by rememberUpdatedState(lastIndex)
    val curMove by rememberUpdatedState(onMove)
    val scope = rememberCoroutineScope()
    return this.pointerInput(key) {
        detectDragGestures(
            onDragStart = { state.draggingKey = key; state.offset = 0f },
            onDragEnd = {
                scope.launch {
                    val a = Animatable(state.offset)
                    a.animateTo(0f, spring(stiffness = 600f)) { state.offset = value }
                    state.draggingKey = null
                }
            },
            onDragCancel = { state.offset = 0f; state.draggingKey = null },
            onDrag = { change, d ->
                change.consume()
                state.offset += d.y
                if (state.offset > rowPx / 2f && curIndex < curLast) {
                    curMove(curIndex, curIndex + 1)
                    state.offset -= rowPx
                } else if (state.offset < -rowPx / 2f && curIndex > 0) {
                    curMove(curIndex, curIndex - 1)
                    state.offset += rowPx
                }
            }
        )
    }
}

/** À poser sur la ligne (dans un LazyColumn) : suit le doigt si elle est saisie, sinon s'anime vers sa place. */
@OptIn(ExperimentalFoundationApi::class)
fun LazyItemScope.reorderItem(state: DragReorderState, key: Any): Modifier =
    if (state.draggingKey == key) {
        Modifier.zIndex(1f).graphicsLayer {
            translationY = state.offset
            shadowElevation = 24f
        }
    } else {
        Modifier.animateItemPlacement()
    }
