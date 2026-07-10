package com.tony.appbooster.domain.usecase.analysis

import com.tony.appbooster.domain.model.common.Resource
import com.tony.appbooster.domain.model.common.ResourceError
import com.tony.appbooster.domain.repository.AdbRepository
import com.tony.appbooster.domain.scheduler.AnalysisWorkScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [StopAnalysisUseCase].
 */
class StopAnalysisUseCaseTest {

    private lateinit var scheduler: AnalysisWorkScheduler
    private lateinit var cancelWorkUseCase: CancelAnalysisWorkUseCase
    private lateinit var cancelRepoUseCase: CancelAnalysisUseCase
    private lateinit var repository: AdbRepository
    private lateinit var useCase: StopAnalysisUseCase
    private lateinit var calls: MutableList<String>

    @Before
    fun setUp() {
        calls = mutableListOf()
        scheduler = mockk {
            every { cancel() } answers {
                calls += "work"
                Unit
            }
        }
        cancelWorkUseCase = CancelAnalysisWorkUseCase(scheduler)
        repository = mockk()
        cancelRepoUseCase = CancelAnalysisUseCase(repository)
        useCase = StopAnalysisUseCase(cancelWorkUseCase, cancelRepoUseCase)
    }

    @Test
    fun `given both cancellations succeed when invoke then repository cancel happens before work cancel`() = runTest {
        coEvery { repository.cancelAnalysis() } coAnswers {
            calls += "repo"
            Resource.Success(Unit)
        }

        val result = useCase()

        assertTrue(result is Resource.Success)
        assertEquals(listOf("repo", "work"), calls)
        coVerify(exactly = 1) { repository.cancelAnalysis() }
        verify(exactly = 1) { scheduler.cancel() }
    }

    @Test
    fun `given repository cancellation fails when invoke then returns repository error and still cancels work`() = runTest {
        val error = ResourceError.LogicError("nothing running")
        coEvery { repository.cancelAnalysis() } coAnswers {
            calls += "repo"
            Resource.Error(error)
        }

        val result = useCase()

        assertTrue(result is Resource.Error)
        assertEquals(listOf("repo", "work"), calls)
        coVerify(exactly = 1) { repository.cancelAnalysis() }
        verify(exactly = 1) { scheduler.cancel() }
    }
}
