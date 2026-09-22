package com.nexus.aichat.domain.usecase

import com.nexus.aichat.core.common.result.NexusResult
import com.nexus.aichat.core.model.ModelInfo
import com.nexus.aichat.domain.repository.ProviderRepository
import javax.inject.Inject

/**
 * "Fetch models": refresh a provider's catalogue, then keep the user's current selection if it still
 * exists. Losing a carefully chosen model on every refresh is the sort of paper cut users remember.
 */
class FetchModelsUseCase @Inject constructor(
    private val providerRepository: ProviderRepository,
) {

    suspend operator fun invoke(providerId: String): NexusResult<List<ModelInfo>> =
        providerRepository.fetchModels(providerId)
}
