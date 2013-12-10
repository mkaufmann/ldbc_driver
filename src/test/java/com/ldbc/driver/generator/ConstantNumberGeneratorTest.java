package com.ldbc.driver.generator;

import java.util.Iterator;

import com.google.common.collect.Range;
import com.ldbc.driver.util.Histogram;
import com.ldbc.driver.util.Bucket.NumberRangeBucket;

public class ConstantNumberGeneratorTest extends NumberGeneratorTest<Long, Long>
{
    private final long constant = 42;

    @Override
    public double getMeanTolerance()
    {
        return 0.0;
    }

    @Override
    public double getDistributionTolerance()
    {
        return 0.0;
    }

    @Override
    public Iterator<Long> getGeneratorImpl()
    {
        return getGeneratorFactory().constant( constant );
    }

    @Override
    public Histogram<Long, Long> getExpectedDistribution()
    {
        Histogram<Long, Long> expectedDistribution = new Histogram<Long, Long>( 0l );
        expectedDistribution.addBucket( new NumberRangeBucket<Long>( Range.closedOpen( 41.99d, 42.01d ) ), 1l );
        return expectedDistribution;
    }

    @Override
    public double getExpectedMean()
    {
        return 42.0;
    }
}
