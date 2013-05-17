package com.ldbc.generator;


public class MinMaxGeneratorWrapper<T extends Number> extends Generator<T>
{
    private T min = null;
    private T max = null;
    private final Generator<T> generator;

    public MinMaxGeneratorWrapper( Generator<T> generator, T initialMin, T initialMax )
    {
        super( generator.getRandom() );
        this.min = initialMin;
        this.max = initialMax;
        this.generator = generator;
    }

    @Override
    protected T doNext() throws GeneratorException
    {
        T next = generator.doNext();
        min = ( next.doubleValue() < min.doubleValue() ) ? next : min;
        max = ( next.doubleValue() > max.doubleValue() ) ? next : max;
        return next;
    }

    public final T getMin()
    {
        return min;
    }

    public final T getMax()
    {
        return max;
    }

    @Override
    public String toString()
    {
        return "MinMaxGeneratorWrapper [min=" + min + ", max=" + max + ", generator=" + generator + "]";
    }
}