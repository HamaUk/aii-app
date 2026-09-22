package com.nexus.aichat.domain.usecase

import com.nexus.aichat.core.common.result.NexusResult
import com.nexus.aichat.core.model.ModelInfo
import com.nexus.aichat.domain.repository.ProviderRepository
import javax.inject.Inject

/**
 * Latency test for one model.
 *
 * Worth a use case of its own because the *meaning* of the result matters more than the call: a ping is
 * a real 1-token completion, so it proves the key, the model id, the request shape and the network in
 * one shot. The model list therefore shows a green/amber/red chip that means something, instead of a
 * decorative dot.
 */
class PingModelUseCase @Inject constructor(
    private val providerRepository: ProviderRepository,
) {

    suspend operator fun invoke(providerId: String, modelId: String): NexusResult<ModelInfo> =
        providerRepository.pingModel(providerId, modelId)
}
