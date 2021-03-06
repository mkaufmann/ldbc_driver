package com.ldbc.driver.runtime.metrics;

import com.ldbc.driver.Operation;
import com.ldbc.driver.control.LoggingServiceFactory;
import com.ldbc.driver.csv.simple.SimpleCsvFileWriter;
import com.ldbc.driver.runtime.ConcurrentErrorReporter;
import com.ldbc.driver.runtime.metrics.sbe.MetricsEvent;
import com.ldbc.driver.temporal.TimeSource;
import com.lmax.disruptor.EventHandler;
import uk.co.real_logic.sbe.codec.java.DirectBuffer;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicStampedReference;

import static java.lang.String.format;

class DisruptorSbeMetricsEventHandler implements EventHandler<DirectBuffer>
{
    private final AtomicStampedReference<WorkloadStatusSnapshot> statusSnapshotReference =
            new AtomicStampedReference<>( null, 0 );
    private final AtomicStampedReference<WorkloadResultsSnapshot> resultsSnapshotReference =
            new AtomicStampedReference<>( null, 0 );

    private final MetricsManager metricsManager;
    private final ConcurrentErrorReporter errorReporter;
    private final SimpleCsvFileWriter csvResultsLogWriter;
    private final TimeUnit unit;
    private long processedEventCount = 0l;
    private final String[] operationNames;
    private final MetricsEvent metricsEvent;
    private final String[] csvResultsLogRow;

    DisruptorSbeMetricsEventHandler( ConcurrentErrorReporter errorReporter,
            SimpleCsvFileWriter csvResultsLogWriter,
            TimeUnit unit,
            TimeSource timeSource,
            long maxRuntimeDurationAsNano,
            Map<Integer,Class<? extends Operation>> operationTypeToClassMapping,
            LoggingServiceFactory loggingServiceFactory ) throws MetricsCollectionException
    {
        this.errorReporter = errorReporter;
        this.csvResultsLogWriter = csvResultsLogWriter;
        this.unit = unit;
        this.metricsManager = new MetricsManager(
                timeSource,
                unit,
                maxRuntimeDurationAsNano,
                operationTypeToClassMapping,
                loggingServiceFactory );
        operationNames = MetricsManager.toOperationNameArray( operationTypeToClassMapping );
        this.metricsEvent = new MetricsEvent();
        this.csvResultsLogRow = new String[5];
    }

    AtomicStampedReference<WorkloadStatusSnapshot> statusSnapshot()
    {
        return statusSnapshotReference;
    }

    AtomicStampedReference<WorkloadResultsSnapshot> resultsSnapshot()
    {
        return resultsSnapshotReference;
    }

    long processedEventCount()
    {
        return processedEventCount;
    }

    @Override
    public void onEvent( DirectBuffer event, long l, boolean b ) throws Exception
    {
        metricsEvent.wrapForDecode(
                event,
                DisruptorSbeMetricsEvent.MESSAGE_HEADER_SIZE,
                DisruptorSbeMetricsEvent.ACTING_BLOCK_LENGTH,
                DisruptorSbeMetricsEvent.ACTING_VERSION
        );

        switch ( metricsEvent.eventType() )
        {
        case DisruptorJavolutionMetricsEvent.SUBMIT_RESULT:
        {
            int operationType = metricsEvent.operationType();
            long scheduledStartTimeAsMilli = metricsEvent.scheduledStartTimeAsMilli();
            long actualStartTimeAsMilli = metricsEvent.actualStartTimeAsMilli();
            long runDurationAsNano = metricsEvent.runDurationAsNano();
            int resultCode = metricsEvent.resultCode();
            long originalStartTime = metricsEvent.originalStartTime();

            if ( null != csvResultsLogWriter )
            {
                // TODO benchmark
//					csvResultsLogRow[0] = operationNames[operationType];
//					csvResultsLogRow[1] = Long.toString(scheduledStartTimeAsMilli);
//					csvResultsLogRow[2] = Long.toString(actualStartTimeAsMilli);
//					csvResultsLogRow[3] = Long.toString(unit.convert(runDurationAsNano, TimeUnit.NANOSECONDS));
//					csvResultsLogRow[4] = Integer.toString(resultCode);
//					csvResultsLogWriter.writeRow(csvResultsLogRow);
                csvResultsLogWriter.writeRow(
                        operationNames[operationType],
                        Long.toString( scheduledStartTimeAsMilli ),
                        Long.toString( actualStartTimeAsMilli ),
                        Long.toString( unit.convert( runDurationAsNano, TimeUnit.NANOSECONDS ) ),
                        Integer.toString( resultCode ),
                        Long.toString(originalStartTime)
                );
            }
            metricsManager.measure( actualStartTimeAsMilli, runDurationAsNano, operationType );
            processedEventCount++;
            break;
        }
        case DisruptorJavolutionMetricsEvent.WORKLOAD_STATUS:
        {
            WorkloadStatusSnapshot newStatus = metricsManager.status();
            WorkloadStatusSnapshot oldStatus;
            int oldStamp;
            do
            {
                oldStatus = statusSnapshotReference.getReference();
                oldStamp = statusSnapshotReference.getStamp();
            }
            while ( false == statusSnapshotReference.compareAndSet( oldStatus, newStatus, oldStamp, oldStamp + 1 ) );
            break;
        }
        case DisruptorJavolutionMetricsEvent.WORKLOAD_RESULT:
        {
            WorkloadResultsSnapshot newResults = metricsManager.snapshot();
            WorkloadResultsSnapshot oldResults;
            int oldStamp;
            do
            {
                oldResults = resultsSnapshotReference.getReference();
                oldStamp = resultsSnapshotReference.getStamp();
            }
            while ( false == resultsSnapshotReference.compareAndSet( oldResults, newResults, oldStamp, oldStamp + 1 ) );
            break;
        }
        default:
        {
            errorReporter.reportError( this, format( "Encountered unexpected event: %s", event.toString() ) );
            break;
        }
        }
    }
}