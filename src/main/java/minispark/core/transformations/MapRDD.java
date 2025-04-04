package minispark.core.transformations;

import minispark.core.MiniRDD;
import minispark.core.Partition;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

public class MapRDD<T, R> implements MiniRDD<R> {
    private final MiniRDD<T> parent;
    private final Function<T, R> mapFunction;
    private final Partition[] partitions;

    public MapRDD(MiniRDD<T> parent, Function<T, R> f) {
        this.parent = parent;
        this.mapFunction = f;
        this.partitions = parent.getPartitions();
    }

    @Override
    public Partition[] getPartitions() {
        return partitions;
    }

    @Override
    public Iterator<R> compute(Partition split) {
        Iterator<T> parentIter = parent.compute(split);
        return new Iterator<R>() {
            @Override
            public boolean hasNext() {
                return parentIter.hasNext();
            }

            @Override
            public R next() {
                return mapFunction.apply(parentIter.next());
            }
        };
    }

    @Override
    public List<MiniRDD<?>> getDependencies() {
        return Collections.singletonList(parent);
    }

    @Override
    public List<String> getPreferredLocations(Partition split) {
        return parent.getPreferredLocations(split);
    }

    @Override
    public <U> MiniRDD<U> map(Function<R, U> f) {
        return new MapRDD<>(this, f);
    }

    @Override
    public MiniRDD<R> filter(Predicate<R> f) {
        return new FilterRDD<>(this, f);
    }

    @Override
    public List<R> collect() {
        List<R> result = new ArrayList<>();
        for (Partition partition : getPartitions()) {
            Iterator<R> iter = compute(partition);
            while (iter.hasNext()) {
                result.add(iter.next());
            }
        }
        return result;
    }
} 