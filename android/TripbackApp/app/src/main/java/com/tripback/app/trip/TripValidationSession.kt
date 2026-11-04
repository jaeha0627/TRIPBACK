package com.tripback.app.trip

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ValidationUiState(val loading: Boolean = false, val result: ValidationResult? = null, val error: String? = null)

/** Main-thread owner. Editing, stopping, leaving and cancellation invalidate the same request generation. */
class TripValidationSession(private val scope: CoroutineScope, private val request: suspend (ValidationRequest) -> ValidationResult) {
    private val gate = PlanRequestGate()
    private var job: Job? = null
    private val mutableState = MutableStateFlow(ValidationUiState())
    val state = mutableState.asStateFlow()

    fun submit(input: ValidationRequest) {
        val token = gate.begin() ?: return
        mutableState.value = ValidationUiState(loading = true)
        job = scope.launch {
            try {
                val result = request(input)
                if (gate.complete(token)) mutableState.value = ValidationUiState(result = result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (gate.complete(token)) mutableState.value = ValidationUiState(error = validationErrorMessage(failure))
            }
        }
    }

    fun cancel() {
        gate.invalidate()
        job?.cancel()
        job = null
        mutableState.value = ValidationUiState()
    }
}
