package com.streamiax.feature.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamiax.domain.model.MediaItem
import com.streamiax.domain.model.MediaSource
import com.streamiax.domain.model.MediaType
import com.streamiax.domain.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class CatalogState(
    val items: List<MediaItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val selectedTypeIndex: Int = 0,
)

@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val repository: MediaRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CatalogState())
    val state = _state.asStateFlow()

    init { load() }

    fun selectType(index: Int) {
        _state.update { it.copy(selectedTypeIndex = index, isLoading = true, items = emptyList()) }
        load()
    }

    private fun load() {
        val type = MediaType.entries[_state.value.selectedTypeIndex]
        repository.catalog(source = MediaSource.Stremio, type = type)
            .onEach { items -> _state.update { it.copy(items = items, isLoading = false) } }
            .catch { e -> _state.update { it.copy(error = e.message, isLoading = false) } }
            .launchIn(viewModelScope)
    }
}
