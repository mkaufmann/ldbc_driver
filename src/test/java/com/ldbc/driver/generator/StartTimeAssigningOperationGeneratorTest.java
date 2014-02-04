package com.ldbc.driver.generator;

import java.util.Iterator;

import com.ldbc.driver.Operation;
import com.ldbc.driver.util.Function1;
import com.ldbc.driver.util.RandomDataGeneratorFactory;
import com.ldbc.driver.temporal.Duration;
import com.ldbc.driver.temporal.Time;

import org.junit.Test;

import static org.junit.Assert.*;
import static org.hamcrest.CoreMatchers.*;

public class StartTimeAssigningOperationGeneratorTest
{
    @Test
    public void shouldAssignStartTimesToOperations()
    {
        // Given
        long firstNanoTime = 1000;
        long incrementNanoTimeBy = 100;
        int testIterations = 10;

        // When
        Iterator<Operation<?>> operationGenerator = new LimitGenerator<Operation<?>>( new OperationGenerator(),
                testIterations );

        Function1<Long, Time> timeFromLongFun = new Function1<Long, Time>()
        {
            @Override
            public Time apply( Long from )
            {
                return Time.fromNano( from );
            }
        };

        Iterator<Long> countGenerator = new GeneratorFactory( new RandomDataGeneratorFactory() ).incrementing(
                firstNanoTime, incrementNanoTimeBy );
        Iterator<Time> counterStartTimeGenerator = new MappingGenerator<Long, Time>( countGenerator, timeFromLongFun );
        Iterator<Operation<?>> startTimeOperationGenerator = new StartTimeAssigningOperationGenerator(
                counterStartTimeGenerator, operationGenerator );

        // Then
        int count = 0;
        Time lastTime = Time.fromNano( firstNanoTime ).minus( Duration.fromNano( incrementNanoTimeBy ) );
        while ( startTimeOperationGenerator.hasNext() )
        {
            Operation<?> operation = startTimeOperationGenerator.next();
            assertThat( operation.scheduledStartTime(), is( lastTime.plus( Duration.fromNano( incrementNanoTimeBy ) ) ) );
            lastTime = operation.scheduledStartTime();
            count++;
        }
        assertThat( count, is( testIterations ) );
    }

    static class OperationGenerator extends Generator<Operation<?>>
    {
        @Override
        protected Operation<?> doNext() throws GeneratorException
        {
            return new Operation<Object>()
            {
            };
        }
    }
}
