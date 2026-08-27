package it.zawardo.treni.ui.train

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.domain.model.TrainRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrainNumberUiState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<TrainRef> = emptyList(),
    val message: String? = null,
)

class TrainNumberViewModel : ViewModel() {

    private val trains = ServiceLocator.trainStatusRepository

    private val _state = MutableStateFlow(TrainNumberUiState())
    val state: StateFlow<TrainNumberUiState> = _state.asStateFlow()

    fun onQueryChange(text: String) {
        // Solo cifre: i numeri di treno non contengono lettere e la tastiera e' numerica.
        val cleaned = text.filter { it.isDigit() }.take(6)
        _state.update { it.copy(query = cleaned, message = null) }
    }

    fun resolve() {
        val number = _state.value.query
        if (number.isBlank()) return

        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null, results = emptyList()) }
            val refs = runCatching { trains.resolve(number) }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    loading = false,
                    results = refs,
                    message = if (refs.isEmpty()) {
                        "Nessun treno $number in circolazione oggi."
                    } else {
                        null
                    },
                )
            }
        }
    }
}
