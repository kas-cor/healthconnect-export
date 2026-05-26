package com.healthconnect.export.repository

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.*
import java.lang.reflect.Field
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking

@RunWith(MockitoJUnitRunner.Silent::class)
class HealthConnectRepositoryTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockClient: HealthConnectClient

    private lateinit var repo: HealthConnectRepository

    @Before
    fun setup() {
        repo = HealthConnectRepository(mockContext)

        // Inject mocked client via reflection to bypass HealthConnectClient.getSdkStatus()
        val clientField: Field = HealthConnectRepository::class.java.getDeclaredField("client")
        clientField.isAccessible = true
        clientField.set(repo, mockClient)
    }

    // ============================
    // filterByPreferredOrigin Tests
    // ============================

    @Test
    fun `filterByPreferredOrigin empty input returns empty`() {
        val result = repo.filterByPreferredOrigin(emptyList<StepsRecord>(), null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `filterByPreferredOrigin single source returns all records`() {
        val records = listOf(
            mockedStepsRecord(1000, "com.mi.health"),
            mockedStepsRecord(2000, "com.mi.health")
        )
        val result = repo.filterByPreferredOrigin(records, null)
        assertEquals(2, result.size)
    }

    @Test
    fun `filterByPreferredOrigin picks first preferred source`() {
        val records = listOf(
            mockedStepsRecord(100, "com.dummy.other"),
            mockedStepsRecord(200, "com.xiaomi.hm.health"),
            mockedStepsRecord(300, "com.mi.health"),
            mockedStepsRecord(400, "com.dummy.other")
        )
        val result = repo.filterByPreferredOrigin(records, null)
        assertEquals(1, result.size)
        assertEquals(300L, result.single().let { it as StepsRecord }.count)
    }

    @Test
    fun `filterByPreferredOrigin respects preferred packages order`() {
        val records = listOf(
            mockedStepsRecord(100, "com.google.android.apps.fitness"),
            mockedStepsRecord(200, "com.mi.health"),
            mockedStepsRecord(300, "com.xiaomi.hm.health")
        )
        val result = repo.filterByPreferredOrigin(records, null)
        assertEquals(1, result.size)
        assertEquals(200L, result.single().let { it as StepsRecord }.count)
    }

    @Test
    fun `filterByPreferredOrigin user selected source is used`() {
        val records = listOf(
            mockedStepsRecord(100, "com.mi.health"),
            mockedStepsRecord(200, "com.fitbit.FitbitMobile"),
            mockedStepsRecord(300, "com.mobvoi.companion.at")
        )
        val result = repo.filterByPreferredOrigin(
            records,
            selectedSourcePackage = "com.fitbit.FitbitMobile"
        )
        assertEquals(1, result.size)
        assertEquals(200L, result.single().let { it as StepsRecord }.count)
    }

    @Test
    fun `filterByPreferredOrigin selected not found falls back to preferred`() {
        val records = listOf(
            mockedStepsRecord(100, "com.mi.health"),
            mockedStepsRecord(200, "com.fitbit.FitbitMobile")
        )
        val result = repo.filterByPreferredOrigin(
            records,
            selectedSourcePackage = "com.nonexistent.app"
        )
        assertEquals(1, result.size)
        assertEquals(100L, result.single().let { it as StepsRecord }.count)
    }

    @Test
    fun `filterByPreferredOrigin no preferred picks source with most records`() {
        val records = listOf(
            mockedStepsRecord(100, "com.a.other"),
            mockedStepsRecord(200, "com.a.other"),
            mockedStepsRecord(300, "com.b.other"),
            mockedStepsRecord(400, "com.b.other"),
            mockedStepsRecord(500, "com.b.other")
        )
        val result = repo.filterByPreferredOrigin(records, null)
        assertEquals(3, result.size)
        result.forEach {
            assertTrue((it as StepsRecord).count in listOf(300L, 400L, 500L))
        }
    }

    @Test
    fun `filterByPreferredOrigin all unknown uses max records fallback`() {
        val records = listOf(
            mockedStepsRecord(100, null),
            mockedStepsRecord(200, null),
            mockedStepsRecord(300, null)
        )
        val result = repo.filterByPreferredOrigin(records, null)
        assertEquals(3, result.size)
    }

    @Test
    fun `filterByPreferredOrigin multiple sources with same origin all returned`() {
        val records = listOf(
            mockedStepsRecord(1000, "com.mobvoi.companion.at"),
            mockedStepsRecord(2000, "com.mobvoi.companion.at"),
            mockedStepsRecord(3000, "com.mobvoi.companion.at")
        )
        val result = repo.filterByPreferredOrigin(records, null)
        assertEquals(3, result.size)
        assertEquals(listOf(1000L, 2000L, 3000L), result.map { (it as StepsRecord).count })
    }

    @Test
    fun `filterByPreferredOrigin mixed known and unknown picks preferred`() {
        val records = listOf(
            mockedStepsRecord(100, "com.unknown.app"),
            mockedStepsRecord(200, "com.samsung.android.wearable.health"),
            mockedStepsRecord(300, null)
        )
        val result = repo.filterByPreferredOrigin(records, null)
        assertEquals(1, result.size)
        assertEquals(200L, result.single().let { it as StepsRecord }.count)
    }

    // ============================
    // getAvailableSources Tests
    // ============================

    @Test
    fun `getAvailableSources returns sources from steps and heart rate`() {
        runBlocking {
            val stepsRecords = listOf(mockedStepsRecord(1000, "com.mi.health"))
            val hrRecords = listOf(mockedHeartRateRecord("com.fitbit.FitbitMobile"))

            stubClientReadRecords(mockClient, stepsRecords, hrRecords)

            val sources = repo.getAvailableSources()
            assertEquals(2, sources.size)
            assertTrue(sources.contains("com.mi.health"))
            assertTrue(sources.contains("com.fitbit.FitbitMobile"))
        }
    }

    @Test
    fun `getAvailableSources no data returns empty set`() {
        runBlocking {
            stubClientReadRecords(mockClient, emptyList(), emptyList())

            val sources = repo.getAvailableSources()
            assertTrue(sources.isEmpty())
        }
    }

    @Test
    fun `getAvailableSources only steps has data`() {
        runBlocking {
            val stepsRecords = listOf(mockedStepsRecord(1000, "com.mobvoi.companion.at"))
            stubClientReadRecords(mockClient, stepsRecords, emptyList())

            val sources = repo.getAvailableSources()
            assertEquals(1, sources.size)
            assertTrue(sources.contains("com.mobvoi.companion.at"))
        }
    }

    @Test
    fun `getAvailableSources client exception handled gracefully`() {
        runBlocking {
        whenever(mockClient.readRecords(any<ReadRecordsRequest<*>>()))
            .thenThrow(RuntimeException("Network error"))

            val sources = repo.getAvailableSources()
            assertTrue(sources.isEmpty())
        }
    }

    @Test
    fun `getAvailableSources deduplicates sources across data types`() {
        runBlocking {
            val stepsRecords = listOf(mockedStepsRecord(1000, "com.mi.health"))
            val hrRecords = listOf(mockedHeartRateRecord("com.mi.health"))
            stubClientReadRecords(mockClient, stepsRecords, hrRecords)

            val sources = repo.getAvailableSources()
            assertEquals(1, sources.size)
            assertTrue(sources.contains("com.mi.health"))
        }
    }

    // ============================
    // readAllPages Tests
    // ============================

    @Test
    fun `readAllPages single page returns all records`() {
        runBlocking {
            val records = listOf(
                mockedStepsRecord(1000, "com.mi.health"),
                mockedStepsRecord(2000, "com.mi.health")
            )
            stubSinglePage(mockClient, records)

            val request = createStepsRequest()
            val result = repo.readAllPages(request)
            assertEquals(2, result.size)
            assertEquals(listOf(1000L, 2000L), (result as List<StepsRecord>).map { it.count })
        }
    }

    @Test
    fun `readAllPages multiple pages follows pageToken`() {
        runBlocking {
            val page1 = listOf(mockedStepsRecord(100, "com.mi.health"), mockedStepsRecord(200, "com.mi.health"))
            val page2 = listOf(mockedStepsRecord(300, "com.mi.health"), mockedStepsRecord(400, "com.mi.health"))

            stubMultiPage(mockClient, listOf(Pair(page1, "page2"), Pair(page2, null)))

            val request = createStepsRequest()
            val result = repo.readAllPages(request)
            assertEquals(4, result.size)
            assertEquals(listOf(100L, 200L, 300L, 400L), (result as List<StepsRecord>).map { it.count })
            verify(mockClient, times(2)).readRecords(any<ReadRecordsRequest<*>>())
        }
    }

    @Test
    fun `readAllPages empty response returns empty list`() {
        runBlocking {
            stubSinglePage(mockClient, emptyList<StepsRecord>())

            val request = createStepsRequest()
            val result = repo.readAllPages(request)
            assertTrue(result.isEmpty())
        }
    }

    @Test
    fun `readAllPages client null returns empty`() {
        runBlocking {
            val clientField: Field = HealthConnectRepository::class.java.getDeclaredField("client")
            clientField.isAccessible = true
            clientField.set(repo, null)

            val request = createStepsRequest()
            val result = repo.readAllPages(request)
            assertTrue(result.isEmpty())
        }
    }

    @Test
    fun `readAllPages three pages concatenates all records`() {
        runBlocking {
            val page1 = listOf(mockedStepsRecord(100, "com.mi.health"))
            val page2 = listOf(mockedStepsRecord(200, "com.mi.health"))
            val page3 = listOf(mockedStepsRecord(300, "com.mi.health"))

            stubMultiPage(mockClient, listOf(
                Pair(page1, "p2"),
                Pair(page2, "p3"),
                Pair(page3, null)
            ))

            val request = createStepsRequest()
            val result = repo.readAllPages(request)
            assertEquals(3, result.size)
            assertEquals(listOf(100L, 200L, 300L), (result as List<StepsRecord>).map { it.count })
            verify(mockClient, times(3)).readRecords(any<ReadRecordsRequest<*>>())
        }
    }

    // ============================
    // Mock Helpers
    // ============================

    private fun mockedStepsRecord(count: Long, packageName: String?): StepsRecord {
        val record = mock<StepsRecord>()
        whenever(record.count).thenReturn(count)
        whenever(record.startTime).thenReturn(Instant.now())
        whenever(record.endTime).thenReturn(Instant.now().plusSeconds(3600))

        val metadata = mock<Metadata>()
        if (packageName != null) {
            whenever(metadata.dataOrigin).thenReturn(DataOrigin(packageName))
        } else {
            whenever(metadata.dataOrigin).thenReturn(null)
        }
        whenever(record.metadata).thenReturn(metadata)

        return record
    }

    private fun mockedHeartRateRecord(packageName: String): HeartRateRecord {
        val record = mock<HeartRateRecord>()
        whenever(record.startTime).thenReturn(Instant.now())
        whenever(record.endTime).thenReturn(Instant.now())
        whenever(record.samples).thenReturn(emptyList())

        val metadata = mock<Metadata>()
        whenever(metadata.dataOrigin).thenReturn(DataOrigin(packageName))
        whenever(record.metadata).thenReturn(metadata)

        return record
    }

    @Suppress("UNCHECKED_CAST")
    private fun mockedResponse(records: List<*>, pageToken: String?): ReadRecordsResponse<*> {
        val resp = mock<ReadRecordsResponse<*>>()
        whenever(resp.records).thenReturn(records as List<Record>)
        whenever(resp.pageToken).thenReturn(pageToken)
        return resp
    }

    @Suppress("UNCHECKED_CAST")
    private fun createStepsRequest(): ReadRecordsRequest<StepsRecord> {
        val req = mock<ReadRecordsRequest<*>>()
        whenever(req.recordType).thenReturn(StepsRecord::class)
        whenever(req.timeRangeFilter).thenReturn(
            TimeRangeFilter.between(
                Instant.now().minus(1, ChronoUnit.DAYS),
                Instant.now()
            )
        )
        whenever(req.dataOriginFilter).thenReturn(emptySet())
        whenever(req.ascendingOrder).thenReturn(true)
        whenever(req.pageSize).thenReturn(5000)
        whenever(req.pageToken).thenReturn(null)
        return req as ReadRecordsRequest<StepsRecord>
    }

    /** Stub client.readRecords with an Answer that dispatches by recordType. */
    private fun stubClientReadRecords(
        client: HealthConnectClient,
        stepsRecords: List<StepsRecord>,
        hrRecords: List<HeartRateRecord>
    ) = runBlocking {
        val stepsResp = mockedResponse(stepsRecords, null)
        val hrResp = mockedResponse(hrRecords, null)

        whenever(client.readRecords(any<ReadRecordsRequest<*>>())).thenAnswer { invocation ->
            val request = invocation.getArgument<ReadRecordsRequest<*>>(0)
            when (request.recordType) {
                StepsRecord::class -> stepsResp
                HeartRateRecord::class -> hrResp
                else -> mockedResponse(emptyList<StepsRecord>(), null)
            }
        }
    }

    /** Stub a single page response (no pagination). */
    private fun stubSinglePage(
        client: HealthConnectClient,
        records: List<StepsRecord>
    ) = runBlocking {
        val resp = mockedResponse(records, null)
        whenever(client.readRecords(any<ReadRecordsRequest<*>>())).thenReturn(resp)
    }

    /** Stub multiple pages — returns responses in order. */
    private fun stubMultiPage(
        client: HealthConnectClient,
        pages: List<Pair<List<StepsRecord>, String?>>
    ) = runBlocking {
        val responses = pages.map { (records, token) ->
            mockedResponse(records, token)
        }
        whenever(client.readRecords(any<ReadRecordsRequest<*>>()))
            .thenReturn(responses[0], *responses.drop(1).toTypedArray())
    }
}
