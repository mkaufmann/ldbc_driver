package com.ldbc.driver.runtime.metrics;

import com.ldbc.driver.Operation;
import com.ldbc.driver.control.LoggingServiceFactory;
import com.ldbc.driver.csv.simple.SimpleCsvFileWriter;
import com.ldbc.driver.runtime.ConcurrentErrorReporter;
import com.ldbc.driver.temporal.TimeSource;
import com.lmax.disruptor.LiteBlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.locks.LockSupport;

import static com.ldbc.driver.runtime.metrics.DisruptorJavolutionMetricsEvent.MetricsCollectionEventFactory;
import static com.ldbc.driver.runtime.metrics.DisruptorJavolutionMetricsEvent.SET_AS_REQUEST_WORKLOAD_RESULT;
import static com.ldbc.driver.runtime.metrics.DisruptorJavolutionMetricsEvent.SET_AS_STATUS;
import static com.ldbc.driver.runtime.metrics.DisruptorJavolutionMetricsEvent.SET_AS_SUBMIT_OPERATION_RESULT;
import static java.lang.String.format;

public class DisruptorJavolutionMetricsService implements MetricsService
{
    private static final long SHUTDOWN_WAIT_TIMEOUT_AS_MILLI = TimeUnit.SECONDS.toMillis( 10 );

    public static final String RESULTS_LOG_FILENAME_SUFFIX = "-results_log.csv";
    public static final String RESULTS_METRICS_FILENAME_SUFFIX = "-results.json";
    public static final String RESULTS_CONFIGURATION_FILENAME_SUFFIX = "-configuration.properties";

    // TODO this could come from config, if we had a max_runtime parameter. for now, it can default to something
    public static final long DEFAULT_HIGHEST_EXPECTED_RUNTIME_DURATION_AS_NANO = TimeUnit.MINUTES.toNanos( 90 );

    private final AtomicLong initiatedEvents = new AtomicLong( 0 );
    private final AtomicBoolean shutdown = new AtomicBoolean( false );
    private final TimeSource timeSource;
    private final RingBuffer<DisruptorJavolutionMetricsEvent> ringBuffer;
    private final Disruptor<DisruptorJavolutionMetricsEvent> disruptor;
    private final DisruptorJavolutionMetricsEventHandler eventHandler;
    private final ConcurrentLinkedQueue<DisruptorJavolutionMetricsServiceWriter> metricsServiceWriters;
    private final ExecutorService executor;

    public DisruptorJavolutionMetricsService(
            TimeSource timeSource,
            ConcurrentErrorReporter errorReporter,
            TimeUnit timeUnit,
            long maxRuntimeDurationAsNano,
            SimpleCsvFileWriter csvResultsLogWriter,
            Map<Integer,Class<? extends Operation>> operationTypeToClassMapping,
            LoggingServiceFactory loggingServiceFactory ) throws MetricsCollectionException
    {
        // Specify the size of the ring buffer, must be power of 2.
        int bufferSize = 1024;

        this.executor = Executors.newSingleThreadExecutor();
        // Construct the Disruptor
        disruptor = new Disruptor(
                new MetricsCollectionEventFactory(),
                bufferSize,
                // Executor that will be used to construct new threads for consumers
                Executors.newSingleThreadExecutor(),
                ProducerType.MULTI,
//                new BlockingWaitStrategy()
                new LiteBlockingWaitStrategy()
//                new SleepingWaitStrategy()
//                new YieldingWaitStrategy()
//                new BusySpinWaitStrategy()
//                new TimeoutBlockingWaitStrategy()
//                new PhasedBackoffWaitStrategy()
        );

        // Connect the handler
        eventHandler = new DisruptorJavolutionMetricsEventHandler(
                errorReporter,
                csvResultsLogWriter,
                timeUnit,
                timeSource,
                maxRuntimeDurationAsNano,
                operationTypeToClassMapping,
                loggingServiceFactory
        );

        disruptor.handleEventsWith( eventHandler );
        DisruptorExceptionHandler exceptionHandler = new DisruptorExceptionHandler( errorReporter );
        disruptor.handleExceptionsFor( eventHandler ).with( exceptionHandler );
        disruptor.handleExceptionsWith( exceptionHandler );

        // Start the Disruptor, starts all threads running  & get the ring buffer from the Disruptor to be used for
        // publishing.
        ringBuffer = disruptor.start();

        this.timeSource = timeSource;
        metricsServiceWriters = new ConcurrentLinkedQueue<>();
    }

    @Override
    synchronized public void shutdown() throws MetricsCollectionException
    {
        if ( shutdown.get() )
        {
            throw new MetricsCollectionException( "Metrics service has already been shutdown" );
        }
        long startTimeMs = timeSource.nowAsMilli();
        boolean shutdownSuccessful = false;
        while ( timeSource.nowAsMilli() - startTimeMs < SHUTDOWN_WAIT_TIMEOUT_AS_MILLI )
        {
            if ( eventHandler.processedEventCount() >= initiatedEvents.get() )
            {
                shutdownSuccessful = true;
                break;
            }
            LockSupport.parkNanos( TimeUnit.MILLISECONDS.toNanos( 100 ) );
        }
        if ( false == shutdownSuccessful )
        {
            String errMsg =
                    format( "%s timed out waiting for last operations to complete\n%s/%s operations completed",
                            getClass().getSimpleName(),
                            eventHandler.processedEventCount(),
                            initiatedEvents.get()
                    );
            throw new MetricsCollectionException( errMsg );
        }

        try
        {
            executor.shutdown();
            boolean terminatedSuccessfully =
                    executor.awaitTermination( SHUTDOWN_WAIT_TIMEOUT_AS_MILLI, TimeUnit.MILLISECONDS );
            if ( false == terminatedSuccessfully )
            {
                List<Runnable> stillRunningThreads = executor.shutdownNow();
                if ( false == stillRunningThreads.isEmpty() )
                {
                    String errMsg = format(
                            "%s shutdown before all executor threads could complete\n%s threads were queued for " +
                            "execution but not yet started",
                            getClass().getSimpleName(),
                            stillRunningThreads.size() );
                    throw new MetricsCollectionException( errMsg );
                }
            }
        }
        catch ( Exception e )
        {
            throw new MetricsCollectionException(
                    "Error encountered while trying to shutdown metrics service disruptor executor", e );
        }


        try
        {
            disruptor.shutdown( SHUTDOWN_WAIT_TIMEOUT_AS_MILLI, TimeUnit.MILLISECONDS );
        }
        catch ( TimeoutException e )
        {
            String errMsg = format( "%s timed out waiting for %s to shutdown",
                    getClass().getSimpleName(),
                    disruptor.getClass().getSimpleName()
            );
            throw new MetricsCollectionException( errMsg, e );
        }
        AlreadyShutdownPolicy alreadyShutdownPolicy = new AlreadyShutdownPolicy();
        for ( DisruptorJavolutionMetricsServiceWriter metricsServiceWriter : metricsServiceWriters )
        {
            metricsServiceWriter.setAlreadyShutdownPolicy( alreadyShutdownPolicy );
        }
        shutdown.set( true );
    }

    @Override
    public MetricsServiceWriter getWriter() throws MetricsCollectionException
    {
        if ( shutdown.get() )
        {
            throw new MetricsCollectionException( "Metrics service has already been shutdown" );
        }
        DisruptorJavolutionMetricsServiceWriter metricsServiceWriter =
                new DisruptorJavolutionMetricsServiceWriter( initiatedEvents, ringBuffer, eventHandler );
        metricsServiceWriters.add( metricsServiceWriter );
        return metricsServiceWriter;
    }

    private static class DisruptorJavolutionMetricsServiceWriter implements MetricsServiceWriter
    {
        private final AtomicLong initiatedEvents;
        private final RingBuffer<DisruptorJavolutionMetricsEvent> ringBuffer;
        private final DisruptorJavolutionMetricsEventHandler eventHandler;

        private AlreadyShutdownPolicy alreadyShutdownPolicy = null;

        private DisruptorJavolutionMetricsServiceWriter( AtomicLong initiatedEvents,
                RingBuffer<DisruptorJavolutionMetricsEvent> ringBuffer,
                DisruptorJavolutionMetricsEventHandler eventHandler )
        {
            this.initiatedEvents = initiatedEvents;
            this.ringBuffer = ringBuffer;
            this.eventHandler = eventHandler;
        }

        private void setAlreadyShutdownPolicy( AlreadyShutdownPolicy alreadyShutdownPolicy )
        {
            this.alreadyShutdownPolicy = alreadyShutdownPolicy;
        }

        @Override
        public void submitOperationResult( int operationType, long scheduledStartTimeAsMilli,
                long actualStartTimeAsMilli, long runDurationAsNano, int resultCode, long originalStartTime ) throws MetricsCollectionException
        {
            if ( null != alreadyShutdownPolicy )
            {
                alreadyShutdownPolicy.apply();
            }
            initiatedEvents.incrementAndGet();
            ringBuffer.publishEvent( SET_AS_SUBMIT_OPERATION_RESULT, operationType, scheduledStartTimeAsMilli,
                    actualStartTimeAsMilli, runDurationAsNano, resultCode, originalStartTime );
        }

        @Override
        public WorkloadStatusSnapshot status() throws MetricsCollectionException
        {
            if ( null != alreadyShutdownPolicy )
            {
                alreadyShutdownPolicy.apply();
            }
            AtomicStampedReference<WorkloadStatusSnapshot> statusSnapshotReference = eventHandler.statusSnapshot();
            int oldStamp = statusSnapshotReference.getStamp();
            ringBuffer.publishEvent( SET_AS_STATUS );
            while ( statusSnapshotReference.getStamp() <= oldStamp )
            {
                LockSupport.parkNanos( TimeUnit.MILLISECONDS.toNanos( 100 ) );
            }
            return statusSnapshotReference.getReference();
        }

        @Override
        public WorkloadResultsSnapshot results() throws MetricsCollectionException
        {
            if ( null != alreadyShutdownPolicy )
            {
                alreadyShutdownPolicy.apply();
            }
            AtomicStampedReference<WorkloadResultsSnapshot> resultsSnapshotReference = eventHandler.resultsSnapshot();
            int oldStamp = resultsSnapshotReference.getStamp();
            ringBuffer.publishEvent( SET_AS_REQUEST_WORKLOAD_RESULT );
            while ( resultsSnapshotReference.getStamp() <= oldStamp )
            {
                LockSupport.parkNanos( TimeUnit.MILLISECONDS.toNanos( 100 ) );
            }
            return resultsSnapshotReference.getReference();
        }
    }

    private static class AlreadyShutdownPolicy
    {
        void apply() throws MetricsCollectionException
        {
            throw new MetricsCollectionException( "Metrics service has already been shutdown" );
        }
    }
}
